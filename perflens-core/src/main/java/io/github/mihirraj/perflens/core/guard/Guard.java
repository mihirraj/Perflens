package io.github.mihirraj.perflens.core.guard;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Circuit breaker that protects the host application: when the JVM is under memory or GC
 * pressure, PerfLens stops collecting (and drops its biggest buffers) so it can never be what
 * tips a struggling app over. Collection resumes after a sustained calm period.
 */
public final class Guard implements AutoCloseable {
    /** Consecutive bad checks before pausing (one GC spike is not enough). */
    static final int TRIP_AFTER = 2;
    /** Consecutive calm checks before resuming (hysteresis avoids flapping). */
    static final int RESUME_AFTER = 6;
    /** Resume only once heap-after-GC is this many points below the limit. */
    static final double RESUME_MARGIN = 15.0;

    public enum State { ACTIVE, PAUSED }

    /** @param heapAfterGcPercent old-generation occupancy right after the last collection (0-100) */
    public record Reading(double heapAfterGcPercent, double gcTimePercent) {}

    /** What the guard switches on and off; implemented by PerfLens. */
    public interface Target {
        void pauseCollection(String reason);

        void resumeCollection();
    }

    private final Target target;
    private final Supplier<Reading> reader;
    private final double maxHeapPercent;
    private final double maxGcTimePercent;
    private final Duration interval;
    private State state = State.ACTIVE;
    private String reason;
    private long since = System.currentTimeMillis();
    private int hotStreak;
    private int coolStreak;
    private int trips;
    private Reading last;
    private ScheduledExecutorService scheduler;

    public Guard(Target target, Supplier<Reading> reader, double maxHeapPercent, double maxGcTimePercent,
                 Duration interval) {
        this.target = target;
        this.reader = reader;
        this.maxHeapPercent = maxHeapPercent;
        this.maxGcTimePercent = maxGcTimePercent;
        this.interval = interval;
    }

    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PerfLens-guard");
            t.setDaemon(true);
            return t;
        });
        long ms = Math.max(100, interval.toMillis());
        scheduler.scheduleWithFixedDelay(this::check, ms, ms, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) scheduler.shutdownNow();
        scheduler = null;
    }

    void check() {
        try {
            evaluate(reader.get());
        } catch (RuntimeException e) {
            // a failed reading must never kill the scheduler thread; try again next interval
        }
    }

    /** Applies one reading. Package-visible for tests. */
    synchronized void evaluate(Reading r) {
        last = r;
        boolean hot = r.heapAfterGcPercent() > maxHeapPercent || r.gcTimePercent() > maxGcTimePercent;
        boolean calm = r.heapAfterGcPercent() < maxHeapPercent - RESUME_MARGIN && r.gcTimePercent() < maxGcTimePercent / 2;
        if (state == State.ACTIVE) {
            hotStreak = hot ? hotStreak + 1 : 0;
            if (hotStreak >= TRIP_AFTER) {
                state = State.PAUSED;
                reason = String.format(java.util.Locale.ROOT,
                        "JVM under pressure: heap after GC %.0f%% (limit %.0f%%), GC time %.1f%% (limit %.1f%%)",
                        r.heapAfterGcPercent(), maxHeapPercent, r.gcTimePercent(), maxGcTimePercent);
                since = System.currentTimeMillis();
                trips++;
                coolStreak = 0;
                target.pauseCollection(reason);
            }
        } else {
            coolStreak = calm ? coolStreak + 1 : 0;
            if (coolStreak >= RESUME_AFTER) {
                state = State.ACTIVE;
                reason = null;
                since = System.currentTimeMillis();
                hotStreak = 0;
                target.resumeCollection();
            }
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state.name());
        m.put("reason", reason);
        m.put("since", since);
        m.put("trips", trips);
        m.put("maxHeapAfterGcPercent", maxHeapPercent);
        m.put("maxGcTimePercent", maxGcTimePercent);
        if (last != null) {
            m.put("lastHeapAfterGcPercent", Math.round(last.heapAfterGcPercent() * 10) / 10.0);
            m.put("lastGcTimePercent", Math.round(last.gcTimePercent() * 10) / 10.0);
        }
        return m;
    }

    /**
     * Reads old-generation occupancy after the last GC and the share of wall time spent in GC
     * since the previous reading. Collectors with a single heap pool (ZGC, Shenandoah) use it.
     */
    public static Supplier<Reading> jvmReader() {
        long[] prev = {totalGcMillis(), System.nanoTime()};
        return () -> {
            List<MemoryPoolMXBean> heap = new ArrayList<>();
            for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
                if (p.getType() == MemoryType.HEAP && p.isValid()) heap.add(p);
            }
            List<MemoryPoolMXBean> old = heap.stream()
                    .filter(p -> p.getName().contains("Old") || p.getName().contains("Tenured")).toList();
            List<MemoryPoolMXBean> pools = old.isEmpty() ? heap : old;
            long used = 0;
            long max = 0;
            for (MemoryPoolMXBean p : pools) {
                MemoryUsage after = p.getCollectionUsage();
                MemoryUsage now = p.getUsage();
                used += after != null ? after.getUsed() : now.getUsed();
                long m = now.getMax() > 0 ? now.getMax() : now.getCommitted();
                max += m;
            }
            if (max <= 0) max = Runtime.getRuntime().maxMemory();
            long gc = totalGcMillis();
            long t = System.nanoTime();
            double wallMs = Math.max(1, (t - prev[1]) / 1_000_000.0);
            double gcPct = Math.max(0, gc - prev[0]) * 100.0 / wallMs;
            prev[0] = gc;
            prev[1] = t;
            return new Reading(used * 100.0 / max, Math.min(100, gcPct));
        };
    }

    private static long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long t = gc.getCollectionTime();
            if (t > 0) total += t;
        }
        return total;
    }
}
