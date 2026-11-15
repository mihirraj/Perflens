package io.github.mihirraj.perflens.core.sql;

import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.trace.TraceWindow;
import io.github.mihirraj.perflens.core.util.CallerResolver;
import io.github.mihirraj.perflens.core.util.Exemplar;
import io.github.mihirraj.perflens.core.util.Histogram;
import io.github.mihirraj.perflens.core.util.TopK;
import io.github.mihirraj.perflens.core.util.ValueRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Aggregates every JDBC statement by fingerprint, keeping the slowest executions with real params. */
public final class SqlRegistry {
    private static final int CALLERS_PER_QUERY = 10;
    private static final int CALLER_SAMPLE_UNTIL = 50;
    private static final int RAW_CACHE_LIMIT = 10_000;
    public static final String OVERFLOW = "[other statements: fingerprint cap reached]";
    /**
     * N+1 caller when no application frame is on the stack. Typical for R2DBC: the query runs when
     * the pipeline is subscribed, after the application method that built it has returned.
     */
    public static final String NO_CALLER = "(no application frame on the stack)";

    public static final class SqlStat {
        public final String fingerprint;
        public final String kind;
        public final List<String> paramNames;
        public final Histogram latency = new Histogram();
        public final Map<String, Long> callers = new HashMap<>();
        public final TopK slowest;
        public long errors;
        public long batchExecutions;
        public long firstSeen = System.currentTimeMillis();
        public long lastSeen;

        SqlStat(String fingerprint, String rawSql, int exemplars) {
            this.fingerprint = fingerprint;
            this.kind = SqlNormalizer.kind(rawSql);
            this.paramNames = SqlNormalizer.placeholderNames(rawSql);
            this.slowest = new TopK(exemplars);
        }
    }

    private final PerfLensConfig config;
    private final ValueRenderer renderer;
    private final CallerResolver callers;
    private final Map<String, SqlStat> stats = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprintCache = new ConcurrentHashMap<>();
    private final Histogram poolWait = new Histogram();
    private final TopK poolWaitSlowest;

    public SqlRegistry(PerfLensConfig config, ValueRenderer renderer, CallerResolver callers) {
        this.config = config;
        this.renderer = renderer;
        this.callers = callers;
        this.poolWaitSlowest = new TopK(config.getCapture().getExemplarsPerItem());
    }

    public String fingerprint(String sql) {
        String fp = fingerprintCache.get(sql);
        if (fp == null) {
            fp = SqlNormalizer.normalize(sql);
            if (fingerprintCache.size() < RAW_CACHE_LIMIT) fingerprintCache.put(sql, fp);
        }
        return fp;
    }

    /**
     * Records one executed statement.
     *
     * @param params    bind values of the first parameter set (may be empty)
     * @param batchSize number of parameter sets / statements (1 for non-batch)
     */
    public void record(String sql, long micros, boolean success, List<Object> params, int batchSize,
                       TraceWindow window) {
        record(sql, micros, success, () -> params, batchSize, window);
    }

    /**
     * Same as {@link #record(String, long, boolean, List, int, TraceWindow)}, but extracts the bind
     * values only when this execution is kept as an exemplar or N+1 sample, which is rare.
     */
    public void record(String sql, long micros, boolean success, Supplier<List<Object>> params, int batchSize,
                       TraceWindow window) {
        String fp = fingerprint(sql);
        SqlStat stat = stats.get(fp);
        if (stat == null) {
            if (stats.size() >= config.getSql().getMaxFingerprints()) {
                fp = OVERFLOW;
            }
            String key = fp;
            stat = stats.computeIfAbsent(key, k -> new SqlStat(k, sql, config.getCapture().getExemplarsPerItem()));
        }

        double ms = micros / 1000.0;
        boolean wantExemplar = stat.slowest.accepts(ms) && ms >= 1.0;
        long count;
        synchronized (stat) {
            stat.latency.record(micros);
            if (!success) stat.errors++;
            if (batchSize > 1) stat.batchExecutions++;
            stat.lastSeen = System.currentTimeMillis();
            count = stat.latency.count();
        }

        TraceWindow.SqlRepeat repeat = window == null ? null : window.onSql(fp, micros);
        boolean needRepeatCaller = repeat != null && window.needsCaller(repeat, config.getSql().getNplusoneThreshold());
        boolean needSampleParams = repeat != null && window.needsSampleParams(repeat);

        String caller = null;
        if (wantExemplar || needRepeatCaller || count <= CALLER_SAMPLE_UNTIL) {
            caller = callers.currentUserFrame();
            if (caller != null && count <= CALLER_SAMPLE_UNTIL) {
                synchronized (stat) {
                    if (stat.callers.size() < CALLERS_PER_QUERY || stat.callers.containsKey(caller)) {
                        stat.callers.merge(caller, 1L, Long::sum);
                    }
                }
            }
        }
        if (needRepeatCaller) window.caller(repeat, caller == null ? NO_CALLER : caller);

        List<String> rendered = null;
        if (wantExemplar || needSampleParams) rendered = renderParams(stat, params.get());
        if (needSampleParams) window.addSampleParams(repeat, String.join(", ", rendered));

        if (wantExemplar) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("sql", sql);
            d.put("params", rendered);
            d.put("executableSql", SqlNormalizer.inline(sql, rendered));
            d.put("caller", caller);
            d.put("entrypoint", window == null ? null : window.entrypoint());
            d.put("thread", Thread.currentThread().getName());
            if (batchSize > 1) d.put("batchSize", batchSize);
            if (!success) d.put("failed", true);
            stat.slowest.add(new Exemplar(ms, System.currentTimeMillis(), d));
        }
    }

    private List<String> renderParams(SqlStat stat, List<Object> params) {
        List<String> out = new ArrayList<>(params == null ? 0 : params.size());
        if (params == null) return out;
        for (int i = 0; i < params.size(); i++) {
            if (!config.getCapture().isParams()) {
                out.add("?");
                continue;
            }
            String name = i < stat.paramNames.size() ? stat.paramNames.get(i) : null;
            out.add(renderer.render(name, params.get(i)));
        }
        return out;
    }

    /** Time spent waiting in DataSource.getConnection(). */
    public void recordConnectionAcquire(long micros, TraceWindow window) {
        double ms = micros / 1000.0;
        synchronized (poolWait) {
            poolWait.record(micros);
        }
        if (ms >= 1.0 && poolWaitSlowest.accepts(ms)) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("caller", callers.currentUserFrame());
            d.put("entrypoint", window == null ? null : window.entrypoint());
            d.put("thread", Thread.currentThread().getName());
            poolWaitSlowest.add(new Exemplar(ms, System.currentTimeMillis(), d));
        }
    }

    public Collection<SqlStat> stats() {
        return stats.values();
    }

    public Histogram poolWait() {
        return poolWait;
    }

    public TopK poolWaitSlowest() {
        return poolWaitSlowest;
    }

    public void reset() {
        stats.clear();
        synchronized (poolWait) {
            poolWait.reset();
        }
        poolWaitSlowest.clear();
    }
}
