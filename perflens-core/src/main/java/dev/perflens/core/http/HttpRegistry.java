package dev.perflens.core.http;

import dev.perflens.core.PerfLensConfig;
import dev.perflens.core.trace.TraceWindow;
import dev.perflens.core.util.CallerResolver;
import dev.perflens.core.util.Exemplar;
import dev.perflens.core.util.Histogram;
import dev.perflens.core.util.TopK;
import dev.perflens.core.util.ValueRenderer;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Outbound HTTP calls grouped by method + host + URI template. */
public final class HttpRegistry {
    private static final int MAX_ROUTES = 1_000;

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
            if (stats.size() >= MAX_ROUTES) return;
            stat = stats.computeIfAbsent(key, k -> new HttpStat(k, method, host, route,
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
            d.put("url", maskQuery(url));
            d.put("status", status);
            d.put("caller", callers.currentUserFrame());
            d.put("entrypoint", window == null ? null : window.entrypoint());
            d.put("thread", Thread.currentThread().getName());
            if (error != null) d.put("error", error.getClass().getName() + ": " + error.getMessage());
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
