package dev.perflens.autoconfigure.r2dbc;

import dev.perflens.autoconfigure.PerfLensRef;
import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import io.r2dbc.proxy.core.Binding;
import io.r2dbc.proxy.core.Bindings;
import io.r2dbc.proxy.core.QueryExecutionInfo;
import io.r2dbc.proxy.core.QueryInfo;
import io.r2dbc.proxy.listener.ProxyExecutionListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Feeds R2DBC executions (SQL, bind values, execution time) into the same SQL registry as JDBC,
 * so slow-query findings, exemplars with real parameters and masking work identically.
 *
 * <p>Reactive pipelines hop threads, so there is usually no request window on the completing
 * thread: statistics and exemplars are recorded, per-request N+1 detection is not (yet).
 */
final class PerfLensR2dbcListener implements ProxyExecutionListener {
    private final PerfLensRef perfLens;

    PerfLensR2dbcListener(PerfLensRef perfLens) {
        this.perfLens = perfLens;
    }

    @Override
    public void afterQuery(QueryExecutionInfo exec) {
        PerfLens pl = perfLens.get();
        if (pl == null || !pl.isCollecting() || !pl.config().getSql().isEnabled()) return;
        List<QueryInfo> queries = exec.getQueries();
        if (queries == null || queries.isEmpty()) return;
        long micros = exec.getExecuteDuration() == null ? 0 : exec.getExecuteDuration().toNanos() / 1000;
        long each = micros / queries.size();
        for (QueryInfo q : queries) {
            List<Bindings> sets = q.getBindingsList();
            boolean batch = exec.getType() == io.r2dbc.proxy.core.ExecutionType.BATCH;
            int batchSize = batch ? Math.max(exec.getBatchSize(), queries.size()) : Math.max(1, sets.size());
            List<Object> params = sets.isEmpty() ? List.of() : values(sets.get(0));
            pl.sql().record(q.getQuery(), each, exec.isSuccess(), params, batchSize, TraceContext.current());
        }
    }

    /** Index bindings in order ($1, $2 / ?); named bindings appended after them. */
    private static List<Object> values(Bindings b) {
        List<Object> out = new ArrayList<>();
        for (Binding binding : b.getIndexBindings()) out.add(value(binding));
        for (Binding binding : b.getNamedBindings()) out.add(value(binding));
        return out;
    }

    private static Object value(Binding binding) {
        if (binding.getBoundValue() == null || binding.getBoundValue().isNull()) return null;
        Object v = binding.getBoundValue().getValue();
        // Spring's DatabaseClient binds io.r2dbc.spi.Parameter wrappers (InParameter): show the real value
        while (v instanceof io.r2dbc.spi.Parameter p) {
            Object inner = p.getValue();
            if (inner == v) break;
            v = inner;
        }
        return v;
    }
}
