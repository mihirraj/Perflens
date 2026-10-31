package dev.perflens.core.report;

import dev.perflens.core.PerfLens;
import dev.perflens.core.http.HttpRegistry;
import dev.perflens.core.jfr.JfrProfiler;
import dev.perflens.core.method.MethodRegistry;
import dev.perflens.core.rules.Finding;
import dev.perflens.core.sql.SqlRegistry;
import dev.perflens.core.trace.TraceRegistry;
import dev.perflens.core.util.Histogram;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON-ready report served to the UI and written to snapshots. */
public final class ReportBuilder {
    private static final int TABLE_LIMIT = 200;

    private final PerfLens pl;

    public ReportBuilder(PerfLens pl) {
        this.pl = pl;
    }

    public Map<String, Object> build() {
        List<Finding> findings = pl.findings();
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>(new java.util.TreeMap<>(pl.meta()));
        meta.put("generatedAt", System.currentTimeMillis());
        meta.put("observingSince", pl.resetAt());
        r.put("meta", meta);
        r.put("overview", overview(findings));
        r.put("findings", findings);
        r.put("queries", queries());
        r.put("methods", methods());
        r.put("hotMethods", hotMethods());
        r.put("http", http());
        r.put("endpoints", endpoints());
        r.put("jvm", jvm());
        r.put("flamegraph", pl.profiler().flameGraph(0.2));
        return r;
    }

    public Map<String, Object> overview(List<Finding> findings) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (String s : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW")) bySeverity.put(s, 0L);
        for (Finding f : findings) bySeverity.merge(f.severity(), 1L, Long::sum);
        // quadratic: one critical issue weighs far more than several minor ones
        double penalty = 0;
        for (Finding f : findings) penalty += f.score() * f.score() / 1000.0;
        m.put("healthScore", Math.max(0, Math.round(100 - penalty)));
        m.put("findingsBySeverity", bySeverity);
        m.put("startedAt", pl.startedAt());
        m.put("observingSince", pl.resetAt());
        m.put("now", System.currentTimeMillis());
        m.put("basePackages", pl.callers().basePackages());
        m.put("captureParams", pl.config().getCapture().isParams());
        m.put("profiler", pl.profiler().status());
        m.put("circuitBreaker", pl.guardStatus());
        m.put("cpuSamples", pl.profiler().totalSamples());
        m.put("jfrEvents", pl.profiler().eventsReceived());
        m.put("jfrHandlerErrors", pl.profiler().handlerErrors());
        if (pl.profiler().lastHandlerError() != null) m.put("jfrLastHandlerError", pl.profiler().lastHandlerError());
        m.put("sqlStatements", pl.sql().stats().stream().mapToLong(s -> s.latency.count()).sum());
        m.put("trackedMethods", pl.methods().stats().size());
        m.put("outboundRoutes", pl.http().stats().size());
        m.put("entrypoints", pl.traces().entrypoints().size());
        m.put("javaVersion", Runtime.version().toString());
        return m;
    }

    private List<Map<String, Object>> queries() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SqlRegistry.SqlStat s : pl.sql().stats()) {
            Map<String, Object> row = new LinkedHashMap<>();
            synchronized (s) {
                row.put("sql", s.fingerprint);
                row.put("kind", s.kind);
                latency(row, s.latency);
                row.put("errors", s.errors);
                row.put("batchExecutions", s.batchExecutions);
                row.put("callers", new LinkedHashMap<>(s.callers));
                row.put("lastSeen", s.lastSeen);
            }
            row.put("slowest", s.slowest.snapshot());
            rows.add(row);
        }
        return limitByTotal(rows);
    }

    private List<Map<String, Object>> methods() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MethodRegistry.MethodStat s : pl.methods().stats()) {
            Map<String, Object> row = new LinkedHashMap<>();
            synchronized (s) {
                row.put("method", s.signature);
                row.put("key", s.key);
                latency(row, s.latency);
                row.put("errors", s.errors);
            }
            row.put("slowest", s.slowest.snapshot());
            rows.add(row);
        }
        return limitByTotal(rows);
    }

    private List<Map<String, Object>> hotMethods() {
        JfrProfiler prof = pl.profiler();
        long total = Math.max(1, prof.totalSamples());
        List<JfrProfiler.UserMethodProfile> list = prof.userMethods();
        list.sort(Comparator.comparingLong((JfrProfiler.UserMethodProfile p) -> p.samples).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JfrProfiler.UserMethodProfile p : list) {
            if (rows.size() >= TABLE_LIMIT) break;
            if (p.samples == 0 && p.allocBytes == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", p.method);
            row.put("samples", p.samples);
            row.put("cpuPercent", Math.round(p.samples * 1000.0 / total) / 10.0);
            row.put("allocatedMB", Math.round(p.allocBytes / 1e5) / 10.0);
            row.put("topLines", top(p.lines, 5));
            row.put("topCallees", top(p.callees, 5));
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> http() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HttpRegistry.HttpStat s : pl.http().stats()) {
            Map<String, Object> row = new LinkedHashMap<>();
            synchronized (s) {
                row.put("route", s.key);
                row.put("host", s.host);
                latency(row, s.latency);
                row.put("errors", s.errors);
                row.put("statuses", new LinkedHashMap<>(s.statuses));
            }
            row.put("slowest", s.slowest.snapshot());
            rows.add(row);
        }
        List<Map<String, Object>> sockets = new ArrayList<>();
        pl.profiler().sockets().forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("endpoint", k);
            row.put("slowOps", v.count);
            row.put("totalMs", Math.round(v.totalNanos / 1e5) / 10.0);
            row.put("maxMs", Math.round(v.maxNanos / 1e5) / 10.0);
            row.put("bytes", v.bytes);
            row.put("callers", v.callers);
            sockets.add(row);
        });
        sockets.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("totalMs")).reversed());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("routes", limitByTotal(rows));
        m.put("slowSockets", sockets);
        return m;
    }

    private List<Map<String, Object>> endpoints() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TraceRegistry.EntrypointStat s : pl.traces().entrypoints()) {
            Map<String, Object> row = new LinkedHashMap<>();
            synchronized (s) {
                long n = Math.max(1, s.latency.count());
                row.put("entrypoint", s.name);
                latency(row, s.latency);
                row.put("errors", s.errors);
                row.put("avgSqlQueries", Math.round(s.sqlCount * 10.0 / n) / 10.0);
                row.put("avgSqlMs", Math.round(s.sqlMicros / 100.0 / n) / 10.0);
                row.put("avgHttpCalls", Math.round(s.httpCount * 10.0 / n) / 10.0);
                row.put("avgHttpMs", Math.round(s.httpMicros / 100.0 / n) / 10.0);
            }
            row.put("slowest", s.slowest.snapshot());
            rows.add(row);
        }
        return limitByTotal(rows);
    }

    private Map<String, Object> jvm() {
        Map<String, Object> m = new LinkedHashMap<>();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.put("heapUsedMB", heap.getUsed() / (1024 * 1024));
        m.put("heapMaxMB", heap.getMax() < 0 ? null : heap.getMax() / (1024 * 1024));
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("gc", pl.profiler().gc());
        m.put("topExecutingFrames", entries(pl.profiler().topLeafFrames(25)));
        m.put("topAllocatedTypesBytes", entries(pl.profiler().topAllocatedTypes(15)));
        m.put("locks", waits(pl.profiler().locks()));
        m.put("virtualThreadPinning", waits(pl.profiler().pinned()));
        return m;
    }

    private static List<Map<String, Object>> waits(Map<String, JfrProfiler.WaitStat> src) {
        List<Map<String, Object>> rows = new ArrayList<>();
        src.forEach((k, v) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("where", k);
            row.put("count", v.count);
            row.put("totalMs", Math.round(v.totalNanos / 1e5) / 10.0);
            row.put("maxMs", Math.round(v.maxNanos / 1e5) / 10.0);
            rows.add(row);
        });
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("totalMs")).reversed());
        return rows;
    }

    private static void latency(Map<String, Object> row, Histogram h) {
        row.put("count", h.count());
        row.put("totalMs", Math.round(h.sum() / 100.0) / 10.0);
        row.put("avgMs", Math.round(h.mean() / 100.0) / 10.0);
        row.put("p50Ms", h.percentile(50) / 1000.0);
        row.put("p95Ms", h.percentile(95) / 1000.0);
        row.put("p99Ms", h.percentile(99) / 1000.0);
        row.put("maxMs", h.max() / 1000.0);
    }

    private static List<Map<String, Object>> limitByTotal(List<Map<String, Object>> rows) {
        rows.sort(Comparator.comparingDouble((Map<String, Object> r) -> (Double) r.get("totalMs")).reversed());
        return rows.size() > TABLE_LIMIT ? new ArrayList<>(rows.subList(0, TABLE_LIMIT)) : rows;
    }

    private static <K> Map<String, Long> top(Map<K, Long> m, int n) {
        Map<String, Long> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<K, Long>comparingByValue().reversed()).limit(n)
                .forEach(e -> out.put(String.valueOf(e.getKey()), e.getValue()));
        return out;
    }

    private static List<Map<String, Object>> entries(List<Map.Entry<String, Long>> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", e.getKey());
            m.put("value", e.getValue());
            out.add(m);
        }
        return out;
    }
}
