package dev.perflens.core;

import dev.perflens.core.guard.Guard;
import dev.perflens.core.http.HttpRegistry;
import dev.perflens.core.jfr.JfrProfiler;
import dev.perflens.core.method.MethodRegistry;
import dev.perflens.core.report.ReportBuilder;
import dev.perflens.core.rules.Finding;
import dev.perflens.core.rules.RuleEngine;
import dev.perflens.core.sql.SqlRegistry;
import dev.perflens.core.trace.TraceRegistry;
import dev.perflens.core.util.CallerResolver;
import dev.perflens.core.util.ValueRenderer;

import java.util.List;
import java.util.Map;

/** Facade wiring all collectors together. One instance per application. */
public final class PerfLens implements AutoCloseable, Guard.Target {
    private final PerfLensConfig config;
    private final ValueRenderer renderer;
    private final CallerResolver callers;
    private final SqlRegistry sql;
    private final MethodRegistry methods;
    private final HttpRegistry http;
    private final TraceRegistry traces;
    private final JfrProfiler profiler;
    private final RuleEngine rules;
    private final long startedAt = System.currentTimeMillis();
    private volatile long resetAt = startedAt;
    private volatile boolean collecting = true;
    private final Map<String, Object> meta = new java.util.concurrent.ConcurrentHashMap<>();
    private Guard guard;

    public PerfLens(PerfLensConfig config) {
        this.config = config;
        this.renderer = new ValueRenderer(config.getCapture().getMaxValueLength(), config.getCapture().getMaskNames());
        this.callers = new CallerResolver(config.getBasePackages());
        this.sql = new SqlRegistry(config, renderer, callers);
        this.methods = new MethodRegistry(config, renderer);
        this.http = new HttpRegistry(config, renderer, callers);
        this.traces = new TraceRegistry(config);
        this.profiler = new JfrProfiler(config, callers);
        this.rules = new RuleEngine(this);
    }

    public void start() {
        start(Guard.jvmReader());
    }

    /** Starts collection; the reader supplies JVM pressure readings to the circuit breaker. */
    public synchronized void start(java.util.function.Supplier<Guard.Reading> pressureReader) {
        if (config.getJfr().isEnabled()) profiler.start();
        PerfLensConfig.CircuitBreaker cb = config.getCircuitBreaker();
        if (cb.isEnabled() && guard == null) {
            guard = new Guard(this, pressureReader, cb.getMaxHeapAfterGcPercent(), cb.getMaxGcTimePercent(),
                    cb.getCheckInterval());
            guard.start();
        }
    }

    @Override
    public synchronized void close() {
        if (guard != null) guard.close();
        profiler.close();
    }

    /** Hooks call this first and do nothing while the circuit breaker has paused collection. */
    public boolean isCollecting() {
        return collecting;
    }

    @Override
    public void pauseCollection(String reason) {
        collecting = false;
        profiler.close();
        profiler.reset(); // the flame graph is the largest buffer: give that memory back now
    }

    @Override
    public void resumeCollection() {
        if (config.getJfr().isEnabled()) profiler.start();
        collecting = true;
    }

    /** Circuit-breaker state for the UI, or null when disabled. */
    public Map<String, Object> guardStatus() {
        Guard g = guard;
        return g == null ? null : g.status();
    }

    public List<Finding> findings() {
        return rules.evaluate();
    }

    /** Full JSON-ready report (also what snapshots store). */
    public Map<String, Object> report() {
        return new ReportBuilder(this).build();
    }

    public void reset() {
        sql.reset();
        methods.reset();
        http.reset();
        traces.reset();
        profiler.reset();
        resetAt = System.currentTimeMillis();
    }

    public PerfLensConfig config() { return config; }
    public ValueRenderer renderer() { return renderer; }
    public CallerResolver callers() { return callers; }
    public SqlRegistry sql() { return sql; }
    public MethodRegistry methods() { return methods; }
    public HttpRegistry http() { return http; }
    public TraceRegistry traces() { return traces; }
    public JfrProfiler profiler() { return profiler; }
    /** Application identity (name, version, git commit) recorded in reports and snapshots. */
    public Map<String, Object> meta() { return meta; }
    public long startedAt() { return startedAt; }
    public long resetAt() { return resetAt; }
}
