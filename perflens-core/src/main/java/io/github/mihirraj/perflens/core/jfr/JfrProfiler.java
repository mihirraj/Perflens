package io.github.mihirraj.perflens.core.jfr;

import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.util.CallerResolver;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process JDK Flight Recorder consumer. Builds a CPU flame graph and attributes CPU samples
 * and sampled allocations to the deepest application method on the stack, remembering which
 * library call (callee) and which line inside that method dominated.
 *
 * <p>Note: RecordingStream still writes chunk files to the JFR repository (temp dir); max age and
 * size are capped so disk use stays small.
 */
public final class JfrProfiler implements AutoCloseable {
    private static final int MAX_USER_METHODS = 5_000;
    private static final int MAX_DETAIL_KEYS = 64;
    private static final int MAX_LEAVES = 20_000;
    private static final int MAX_LOCK_KEYS = 500;

    /** Per application-method CPU and allocation attribution. */
    public static final class UserMethodProfile {
        public final String method;
        public long samples;
        public long allocBytes;
        public final Map<Integer, Long> lines = new HashMap<>();
        public final Map<String, Long> callees = new HashMap<>();
        public final Map<String, Long> leaves = new HashMap<>();
        public final Map<String, Long> allocClasses = new HashMap<>();
        /** Library call the method made when the sampled allocation happened (bytes). */
        public final Map<String, Long> allocCallees = new HashMap<>();

        UserMethodProfile(String method) {
            this.method = method;
        }
    }

    public static final class WaitStat {
        public long count;
        public long totalNanos;
        public long maxNanos;
        public long bytes;
        public final Map<String, Long> callers = new HashMap<>();
    }

    private final PerfLensConfig config;
    private final CallerResolver callers;
    private final CallTree cpuTree;
    private final Map<String, UserMethodProfile> userMethods = new HashMap<>();
    private final Map<String, Long> leafFrames = new HashMap<>();
    private final Map<String, Long> allocByClass = new HashMap<>();
    private final Map<String, WaitStat> locks = new HashMap<>();
    private final Map<String, WaitStat> pinned = new HashMap<>();
    private final Map<String, WaitStat> sockets = new HashMap<>();
    private final Map<String, Long> gcCauses = new HashMap<>();
    private long totalSamples;
    private long totalAllocBytes;
    private long gcCount;
    private long gcPauseNanos;
    private long gcMaxPauseNanos;
    private long startedAtMillis;
    private long resetAtMillis;
    private volatile boolean running;
    private volatile String status = "not started";
    private RecordingStream stream;

    public JfrProfiler(PerfLensConfig config, CallerResolver callers) {
        this.config = config;
        this.callers = callers;
        this.cpuTree = new CallTree(config.getJfr().getMaxTreeNodes());
    }

    public synchronized void start() {
        if (running) return;
        PerfLensConfig.Jfr c = config.getJfr();
        try {
            RecordingStream rs = new RecordingStream();
            rs.enable("jdk.ExecutionSample").withPeriod(c.getCpuSampleInterval());
            if (c.isAllocationSampling()) {
                rs.enable("jdk.ObjectAllocationSample").with("throttle", "100/s").withStackTrace();
            }
            rs.enable("jdk.GarbageCollection");
            rs.enable("jdk.JavaMonitorEnter").withThreshold(c.getLockThreshold()).withStackTrace();
            rs.enable("jdk.SocketRead").withThreshold(c.getSocketThreshold()).withStackTrace();
            rs.enable("jdk.SocketWrite").withThreshold(c.getSocketThreshold()).withStackTrace();
            if (Runtime.version().feature() >= 21) {
                rs.enable("jdk.VirtualThreadPinned").withThreshold(c.getVirtualThreadPinThreshold()).withStackTrace();
            }
            rs.setMaxAge(Duration.ofMinutes(1));
            rs.setMaxSize(16L * 1024 * 1024);
            rs.setOrdered(false);
            rs.onEvent("jdk.ExecutionSample", guarded(this::onExecutionSample));
            rs.onEvent("jdk.ObjectAllocationSample", guarded(this::onAllocation));
            rs.onEvent("jdk.GarbageCollection", guarded(this::onGc));
            rs.onEvent("jdk.JavaMonitorEnter", guarded(e -> onWait(e, locks, monitorKey(e))));
            rs.onEvent("jdk.VirtualThreadPinned", guarded(e -> onWait(e, pinned, null)));
            rs.onEvent("jdk.SocketRead", guarded(e -> onSocket(e, "bytesRead")));
            rs.onEvent("jdk.SocketWrite", guarded(e -> onSocket(e, "bytesWritten")));
            rs.onError(t -> status = "error: " + t);
            rs.startAsync();
            stream = rs;
            running = true;
            startedAtMillis = System.currentTimeMillis();
            resetAtMillis = startedAtMillis;
            status = "running";
        } catch (Throwable t) {
            // e.g. a JVM without JFR (some OpenJ9 builds) or a restricted environment
            status = "unavailable: " + t;
        }
    }

    @Override
    public void close() {
        RecordingStream rs;
        synchronized (this) {
            rs = stream;
            stream = null;
            running = false;
            status = "stopped";
        }
        // closed outside the lock: the consumer thread may be waiting on it inside a handler
        if (rs != null) {
            try {
                rs.close();
            } catch (Throwable ignored) {
                // shutting down anyway
            }
        }
    }

    // ---- event handlers (single JFR consumer thread) ----

    private void onExecutionSample(RecordedEvent e) {
        RecordedThread t = e.hasField("sampledThread") ? e.getThread("sampledThread") : null;
        if (t != null && isOwnThread(t.getJavaName())) return;
        RecordedStackTrace st = e.getStackTrace();
        if (st == null) return;
        List<RecordedFrame> frames = st.getFrames();
        int n = frames.size();
        if (n == 0) return;
        String[] classes = new String[n];
        String[] names = new String[n];
        int userIdx = -1;
        for (int i = 0; i < n; i++) {
            RecordedFrame f = frames.get(i);
            String cls = cleanClass(f.getMethod().getType().getName());
            classes[i] = cls;
            names[i] = cls + "." + f.getMethod().getName();
            if (userIdx < 0 && callers.isUserClass(cls)) userIdx = i;
        }
        List<String> path = new ArrayList<>(n + 1);
        // JFR keeps only the top 64 frames (-XX:FlightRecorderOptions:stackdepth=N raises it); group
        // stacks whose root end was cut off instead of mixing them in at the top level.
        if (st.isTruncated()) path.add("[deep stack: root frames cut off by JFR stackdepth]");
        String lastGroup = null;
        for (int i = n - 1; i >= 0; i--) {
            String g = group(classes[i]);
            if (g != null) {
                if (!g.equals(lastGroup)) path.add(g);
                lastGroup = g;
            } else {
                path.add(names[i]);
                lastGroup = null;
            }
        }
        synchronized (this) {
            totalSamples++;
            cpuTree.add(path, 1);
            bump(leafFrames, names[0], 1, MAX_LEAVES);
            if (userIdx >= 0) {
                UserMethodProfile p = profile(names[userIdx]);
                if (p != null) {
                    p.samples++;
                    bumpInt(p.lines, frames.get(userIdx).getLineNumber());
                    bump(p.callees, userIdx > 0 ? names[userIdx - 1] : "(own code)", 1, MAX_DETAIL_KEYS);
                    bump(p.leaves, names[0], 1, MAX_DETAIL_KEYS);
                }
            }
        }
    }

    private void onAllocation(RecordedEvent e) {
        if (isOwnThread(e.getThread() == null ? null : e.getThread().getJavaName())) return;
        long weight = e.hasField("weight") ? e.getLong("weight") : 0;
        RecordedClass cls = e.hasField("objectClass") ? e.getClass("objectClass") : null;
        String type = cls == null ? "?" : prettyType(cls.getName());
        RecordedStackTrace st = e.getStackTrace();
        String user = null;
        String callee = null;
        if (st != null) {
            String previous = null;
            for (RecordedFrame f : st.getFrames()) {
                String c = cleanClass(f.getMethod().getType().getName());
                String name = c + "." + f.getMethod().getName();
                if (callers.isUserClass(c)) {
                    user = name;
                    callee = previous == null ? "(own code)" : previous;
                    break;
                }
                previous = name;
            }
        }
        synchronized (this) {
            totalAllocBytes += weight;
            bump(allocByClass, type, weight, MAX_LEAVES);
            if (user != null) {
                UserMethodProfile p = profile(user);
                if (p != null) {
                    p.allocBytes += weight;
                    bump(p.allocClasses, type, weight, MAX_DETAIL_KEYS);
                    bump(p.allocCallees, callee, weight, MAX_DETAIL_KEYS);
                }
            }
        }
    }

    private void onGc(RecordedEvent e) {
        long pause = e.hasField("sumOfPauses") ? e.getDuration("sumOfPauses").toNanos() : e.getDuration().toNanos();
        String cause = (e.hasField("name") ? e.getString("name") : "GC") + " / " + (e.hasField("cause") ? e.getString("cause") : "?");
        synchronized (this) {
            gcCount++;
            gcPauseNanos += pause;
            gcMaxPauseNanos = Math.max(gcMaxPauseNanos, pause);
            bump(gcCauses, cause, 1, MAX_DETAIL_KEYS);
        }
    }

    private String monitorKey(RecordedEvent e) {
        RecordedClass c = e.hasField("monitorClass") ? e.getClass("monitorClass") : null;
        return c == null ? "?" : c.getName();
    }

    private void onWait(RecordedEvent e, Map<String, WaitStat> target, String prefix) {
        String frame = userFrame(e.getStackTrace());
        String key = prefix == null ? (frame == null ? "(no application frame)" : frame)
                : prefix + " @ " + (frame == null ? "(no application frame)" : frame);
        long nanos = e.getDuration().toNanos();
        synchronized (this) {
            WaitStat s = target.get(key);
            if (s == null) {
                if (target.size() >= MAX_LOCK_KEYS) return;
                s = new WaitStat();
                target.put(key, s);
            }
            s.count++;
            s.totalNanos += nanos;
            s.maxNanos = Math.max(s.maxNanos, nanos);
        }
    }

    private void onSocket(RecordedEvent e, String bytesField) {
        String host = e.hasField("host") ? e.getString("host") : null;
        if (host == null || host.isEmpty()) host = e.hasField("address") ? e.getString("address") : "?";
        String key = host + ":" + (e.hasField("port") ? e.getInt("port") : 0);
        long nanos = e.getDuration().toNanos();
        long bytes = e.hasField(bytesField) ? e.getLong(bytesField) : 0;
        String frame = userFrame(e.getStackTrace());
        synchronized (this) {
            WaitStat s = sockets.get(key);
            if (s == null) {
                if (sockets.size() >= MAX_LOCK_KEYS) return;
                s = new WaitStat();
                sockets.put(key, s);
            }
            s.count++;
            s.totalNanos += nanos;
            s.maxNanos = Math.max(s.maxNanos, nanos);
            s.bytes += Math.max(0, bytes);
            bump(s.callers, frame == null ? "(no application frame)" : frame, 1, 10);
        }
    }

    // ---- helpers ----

    private final java.util.concurrent.atomic.AtomicLong events = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong handlerErrors = new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastHandlerError;

    /** One bad event must never stop the stream: count and remember the error instead. */
    private java.util.function.Consumer<RecordedEvent> guarded(java.util.function.Consumer<RecordedEvent> handler) {
        return e -> {
            events.incrementAndGet();
            try {
                handler.accept(e);
            } catch (Throwable t) {
                handlerErrors.incrementAndGet();
                lastHandlerError = e.getEventType().getName() + ": " + t;
            }
        };
    }

    public long eventsReceived() { return events.get(); }
    public long handlerErrors() { return handlerErrors.get(); }
    public String lastHandlerError() { return lastHandlerError; }

    private String userFrame(RecordedStackTrace st) {
        if (st == null) return null;
        for (RecordedFrame f : st.getFrames()) {
            String c = cleanClass(f.getMethod().getType().getName());
            if (callers.isUserClass(c)) return c + "." + f.getMethod().getName() + ":" + f.getLineNumber();
        }
        return null;
    }

    private UserMethodProfile profile(String method) {
        UserMethodProfile p = userMethods.get(method);
        if (p == null && userMethods.size() < MAX_USER_METHODS) {
            p = new UserMethodProfile(method);
            userMethods.put(method, p);
        }
        return p;
    }

    private static boolean isOwnThread(String name) {
        return name != null && (name.startsWith("JFR ") || name.startsWith("PerfLens"));
    }

    static String cleanClass(String name) {
        int lambda = name.indexOf("$$Lambda");
        if (lambda > 0) return name.substring(0, lambda + 8);
        return name;
    }

    private static String prettyType(String jvmName) {
        return jvmName.startsWith("[") ? arrayName(jvmName) : jvmName;
    }

    private static String arrayName(String n) {
        int dims = 0;
        while (dims < n.length() && n.charAt(dims) == '[') dims++;
        String base = switch (n.length() > dims ? n.charAt(dims) : '?') {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'L' -> n.substring(dims + 1, n.length() - 1);
            default -> n.substring(dims);
        };
        return base + "[]".repeat(dims);
    }

    /** Collapses framework plumbing into one synthetic flame graph node. */
    static String group(String cls) {
        if (cls.contains("$$SpringCGLIB$$") || cls.startsWith("org.springframework.aop.")
                || cls.startsWith("org.springframework.cglib.") || cls.startsWith("jdk.internal.reflect.")
                || cls.startsWith("java.lang.reflect.") || cls.startsWith("jdk.proxy")
                || cls.startsWith("java.lang.invoke.")) return "[proxy / reflection]";
        if (cls.startsWith("org.apache.catalina.") || cls.startsWith("org.apache.coyote.")
                || cls.startsWith("org.apache.tomcat.")) return "[Tomcat]";
        if (cls.startsWith("org.springframework.web.servlet.") || cls.startsWith("org.springframework.web.filter.")
                || cls.startsWith("org.springframework.web.method.") || cls.startsWith("jakarta.servlet.")) return "[Spring MVC]";
        if (cls.startsWith("org.springframework.security.")) return "[Spring Security]";
        if (cls.startsWith("org.springframework.transaction.")) return "[Spring transaction]";
        if (cls.startsWith("io.github.mihirraj.perflens.")) return "[PerfLens]";
        return null;
    }

    private static void bump(Map<String, Long> m, String key, long w, int cap) {
        Long v = m.get(key);
        if (v != null) m.put(key, v + w);
        else if (m.size() < cap) m.put(key, w);
    }

    private static void bumpInt(Map<Integer, Long> m, int key) {
        if (m.containsKey(key) || m.size() < MAX_DETAIL_KEYS) m.merge(key, 1L, Long::sum);
    }

    // ---- read side (any thread) ----

    public boolean isRunning() { return running; }
    public String status() { return status; }

    public synchronized long totalSamples() { return totalSamples; }
    public synchronized long totalAllocBytes() { return totalAllocBytes; }

    public synchronized Map<String, Object> flameGraph(double minPercent) {
        long min = (long) Math.floor(cpuTree.total() * minPercent / 100.0);
        Map<String, Object> m = cpuTree.toJson(min);
        m.put("nodes", cpuTree.nodeCount());
        return m;
    }

    /** Deep copies so callers can read without holding the lock. */
    public synchronized List<UserMethodProfile> userMethods() {
        List<UserMethodProfile> out = new ArrayList<>(userMethods.size());
        for (UserMethodProfile p : userMethods.values()) {
            UserMethodProfile c = new UserMethodProfile(p.method);
            c.samples = p.samples;
            c.allocBytes = p.allocBytes;
            c.lines.putAll(p.lines);
            c.callees.putAll(p.callees);
            c.leaves.putAll(p.leaves);
            c.allocClasses.putAll(p.allocClasses);
            c.allocCallees.putAll(p.allocCallees);
            out.add(c);
        }
        return out;
    }

    public synchronized List<Map.Entry<String, Long>> topLeafFrames(int limit) {
        return top(leafFrames, limit);
    }

    public synchronized List<Map.Entry<String, Long>> topAllocatedTypes(int limit) {
        return top(allocByClass, limit);
    }

    public synchronized Map<String, WaitStat> locks() { return copy(locks); }
    public synchronized Map<String, WaitStat> pinned() { return copy(pinned); }
    public synchronized Map<String, WaitStat> sockets() { return copy(sockets); }

    public synchronized Map<String, Object> gc() {
        Map<String, Object> m = new LinkedHashMap<>();
        long windowMs = Math.max(1, System.currentTimeMillis() - resetAtMillis);
        m.put("collections", gcCount);
        m.put("totalPauseMs", gcPauseNanos / 1_000_000.0);
        m.put("maxPauseMs", gcMaxPauseNanos / 1_000_000.0);
        m.put("pausePercentOfWallTime", (gcPauseNanos / 1_000_000.0) * 100.0 / windowMs);
        m.put("byCollectorAndCause", new LinkedHashMap<>(gcCauses));
        return m;
    }

    public synchronized long observedSinceMillis() { return resetAtMillis; }

    public synchronized void reset() {
        cpuTree.reset();
        userMethods.clear();
        leafFrames.clear();
        allocByClass.clear();
        locks.clear();
        pinned.clear();
        sockets.clear();
        gcCauses.clear();
        totalSamples = 0;
        totalAllocBytes = 0;
        gcCount = 0;
        gcPauseNanos = 0;
        gcMaxPauseNanos = 0;
        resetAtMillis = System.currentTimeMillis();
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Long> m, int limit) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(m.size());
        for (Map.Entry<String, Long> e : m.entrySet()) list.add(Map.entry(e.getKey(), e.getValue()));
        list.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private static Map<String, WaitStat> copy(Map<String, WaitStat> src) {
        Map<String, WaitStat> out = new HashMap<>();
        for (Map.Entry<String, WaitStat> e : src.entrySet()) {
            WaitStat s = new WaitStat();
            WaitStat o = e.getValue();
            s.count = o.count;
            s.totalNanos = o.totalNanos;
            s.maxNanos = o.maxNanos;
            s.bytes = o.bytes;
            s.callers.putAll(o.callers);
            out.put(e.getKey(), s);
        }
        return out;
    }
}
