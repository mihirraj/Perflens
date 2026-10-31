package dev.perflens.core;

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
public final class PerfLens implements AutoCloseable {
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
        if (config.getJfr().isEnabled()) profiler.start();
    }

    @Override
    public void close() {
        profiler.close();
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
    public long startedAt() { return startedAt; }
    public long resetAt() { return resetAt; }
}
