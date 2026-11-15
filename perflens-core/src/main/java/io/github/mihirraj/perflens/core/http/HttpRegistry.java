package io.github.mihirraj.perflens.core.http;

import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.trace.TraceWindow;
import io.github.mihirraj.perflens.core.util.CallerResolver;
import io.github.mihirraj.perflens.core.util.Exemplar;
import io.github.mihirraj.perflens.core.util.Histogram;
import io.github.mihirraj.perflens.core.util.TopK;
import io.github.mihirraj.perflens.core.util.ValueRenderer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Outbound HTTP calls grouped by method + host + URI template. */
public final class HttpRegistry {
    private static final int MAX_ROUTES = 1_000;
    /** Route of the bucket that collects calls once {@link #MAX_ROUTES} distinct routes exist. */
    public static final String OVERFLOW = "[other routes: route cap reached]";

    public static final class HttpStat {
        public final String key;
        public final String method;
        public final String host;
        public final String route;
        public final Histogram latency = new Histogram();
        public final Map<Integer, Long> statuses = new TreeMap<>();
        public final TopK slowest;
        public long errors;

        HttpStat(String key, String method, String host, String route, int exemplars) {
            this.key = key;
            this.method = method;
            this.host = host;
            this.route = route;
            this.slowest = new TopK(exemplars);
        }
    }

    private final PerfLensConfig config;
    private final ValueRenderer renderer;
    private final CallerResolver callers;
    private final Map<String, HttpStat> stats = new ConcurrentHashMap<>();

    public HttpRegistry(PerfLensConfig config, ValueRenderer renderer, CallerResolver callers) {
        this.config = config;
        this.renderer = renderer;
        this.callers = callers;
    }

    /**
     * @param route  URI template when known ("/users/{id}"), otherwise the raw path
     * @param status HTTP status, or 0 when the call failed without a response
     */
    public void record(String method, String host, String route, String url, int status, Throwable error,
                       long startNanos, long endNanos, TraceWindow window) {
        String key = method + " " + host + route;
        if (window != null) window.onHttp(new TraceWindow.HttpCall(startNanos, endNanos, key, status));

        HttpStat stat = stats.get(key);
        if (stat == null) {
            // past the cap, keep counting in one bucket rather than silently dropping calls
            boolean full = stats.size() >= MAX_ROUTES;
            String statKey = full ? OVERFLOW : key;
            String statHost = full ? "*" : host;
            String statRoute = full ? OVERFLOW : route;
            stat = stats.computeIfAbsent(statKey, k -> new HttpStat(k, method, statHost, statRoute,
                    config.getCapture().getExemplarsPerItem()));
        }
        long micros = (endNanos - startNanos) / 1000;
        boolean failed = error != null || status >= 500 || status == 0;
        synchronized (stat) {
            stat.latency.record(micros);
            stat.statuses.merge(status, 1L, Long::sum);
            if (failed) stat.errors++;
        }
        double ms = micros / 1000.0;
        if (ms >= 1.0 && stat.slowest.accepts(ms)) {
            Map<String, Object> d = new LinkedHashMap<>();
            // without value capture the raw path may still hold ids: show the route instead
            d.put("url", config.getCapture().isParams() ? maskQuery(url) : host + route);
            d.put("status", status);
            d.put("caller", callers.currentUserFrame());
            d.put("entrypoint", window == null ? null : window.entrypoint());
            d.put("thread", Thread.currentThread().getName());
            if (error != null) d.put("error", ValueRenderer.describeError(error, config.getCapture().isParams()));
            stat.slowest.add(new Exemplar(ms, System.currentTimeMillis(), d));
        }
    }

    private String maskQuery(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        if (q < 0) return url;
        if (!config.getCapture().isParams()) return url.substring(0, q) + "?***";
        StringBuilder sb = new StringBuilder(url.substring(0, q + 1));
        String[] pairs = url.substring(q + 1).split("&");
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            int eq = pairs[i].indexOf('=');
            String name = eq < 0 ? pairs[i] : pairs[i].substring(0, eq);
            sb.append(eq >= 0 && renderer.isSensitive(name) ? name + "=" + ValueRenderer.MASKED : pairs[i]);
        }
        return sb.toString();
    }

    public Collection<HttpStat> stats() {
        return stats.values();
    }

    public void reset() {
        stats.clear();
    }
}
