package io.github.mihirraj.perflens.core.method;

import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.trace.TraceWindow;
import io.github.mihirraj.perflens.core.util.Exemplar;
import io.github.mihirraj.perflens.core.util.Histogram;
import io.github.mihirraj.perflens.core.util.TopK;
import io.github.mihirraj.perflens.core.util.ValueRenderer;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Timing for application bean methods, keeping the slowest calls together with their arguments
 * and a breakdown of how much of that call was spent in SQL and outbound HTTP.
 */
public final class MethodRegistry {

    public static final class MethodStat {
        /** "com.acme.OrderService.price" (joins with JFR hot-method frames). */
        public final String key;
        /** "com.acme.OrderService.price(Long, int)". */
        public final String signature;
        public final String[] paramNames;
        public final Histogram latency = new Histogram();
        public final TopK slowest;
        public long errors;

        MethodStat(Class<?> targetClass, Method m, int exemplars) {
            this.key = targetClass.getName() + "." + m.getName();
            this.signature = key + "(" + Arrays.stream(m.getParameterTypes())
                    .map(Class::getSimpleName).collect(Collectors.joining(", ")) + ")";
            Parameter[] ps = m.getParameters();
            this.paramNames = new String[ps.length];
            for (int i = 0; i < ps.length; i++) paramNames[i] = ps[i].getName();
            this.slowest = new TopK(exemplars);
        }
    }

    /** Counters of the enclosing trace window before the call started. */
    public record Before(int sqlCount, long sqlMicros, int httpCount, long httpMicros) {
        public static Before of(TraceWindow w) {
            return w == null ? new Before(0, 0, 0, 0)
                    : new Before(w.sqlCount(), w.sqlMicros(), w.httpCount(), w.httpMicros());
        }
    }

    private final PerfLensConfig config;
    private final ValueRenderer renderer;
    private final Map<Method, MethodStat> stats = new ConcurrentHashMap<>();
    private final long exemplarMinMicros;

    public MethodRegistry(PerfLensConfig config, ValueRenderer renderer) {
        this.config = config;
        this.renderer = renderer;
        this.exemplarMinMicros = config.getMethods().getExemplarMinDuration().toNanos() / 1000;
    }

    /** Fast path: the stat for a method seen before, or null. */
    public MethodStat cached(Method method) {
        return stats.get(method);
    }

    /** Returns null when the method cap is reached (call is then not tracked). */
    public MethodStat stat(Method method, Class<?> targetClass) {
        MethodStat s = stats.get(method);
        if (s != null) return s;
        if (stats.size() >= config.getMethods().getMaxMethods()) return null;
        return stats.computeIfAbsent(method,
                m -> new MethodStat(targetClass, m, config.getCapture().getExemplarsPerItem()));
    }

    public void record(MethodStat stat, long micros, Throwable error, Object[] args, Before before, TraceWindow w) {
        synchronized (stat) {
            stat.latency.record(micros);
            if (error != null) stat.errors++;
        }
        double ms = micros / 1000.0;
        if (micros < exemplarMinMicros || !stat.slowest.accepts(ms)) return;

        Map<String, Object> d = new LinkedHashMap<>();
        Map<String, String> renderedArgs = new LinkedHashMap<>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String name = i < stat.paramNames.length ? stat.paramNames[i] : "arg" + i;
                renderedArgs.put(name, config.getCapture().isParams() ? renderer.render(name, args[i]) : "?");
            }
        }
        d.put("args", renderedArgs);
        d.put("entrypoint", w == null ? null : w.entrypoint());
        d.put("thread", Thread.currentThread().getName());
        if (w != null) {
            int sqlCount = w.sqlCount() - before.sqlCount();
            double sqlMs = (w.sqlMicros() - before.sqlMicros()) / 1000.0;
            int httpCount = w.httpCount() - before.httpCount();
            double httpMs = (w.httpMicros() - before.httpMicros()) / 1000.0;
            d.put("sqlQueries", sqlCount);
            d.put("sqlMs", round(sqlMs));
            d.put("httpCalls", httpCount);
            d.put("httpMs", round(httpMs));
            d.put("otherMs", round(Math.max(0, ms - sqlMs - httpMs)));
        }
        if (error != null) {
            String msg = error.getMessage();
            if (msg != null && msg.length() > 300) msg = msg.substring(0, 300) + "...";
            d.put("error", error.getClass().getName() + (msg == null ? "" : ": " + msg));
        }
        stat.slowest.add(new Exemplar(ms, System.currentTimeMillis(), d));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    public Collection<MethodStat> stats() {
        return stats.values();
    }

    public void reset() {
        stats.clear();
    }
}
