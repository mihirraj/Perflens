package dev.perflens.autoconfigure.jdbc;

import dev.perflens.core.PerfLens;
import dev.perflens.core.trace.TraceContext;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Feeds every JDBC execution (SQL text, bind values, nanosecond timing) into PerfLens. */
final class PerfLensQueryListener implements QueryExecutionListener {
    private static final ThreadLocal<long[]> START = ThreadLocal.withInitial(() -> new long[1]);

    private final dev.perflens.autoconfigure.PerfLensRef perfLens;

    PerfLensQueryListener(ObjectProvider<PerfLens> perfLens) {
        this.perfLens = new dev.perflens.autoconfigure.PerfLensRef(perfLens);
    }

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        START.get()[0] = System.nanoTime();
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        long micros = (System.nanoTime() - START.get()[0]) / 1000;
        PerfLens pl = perfLens.get();
        if (pl == null || !pl.isCollecting() || !pl.config().getSql().isEnabled() || queryInfoList.isEmpty()) return;
        long each = micros / queryInfoList.size();
        for (QueryInfo q : queryInfoList) {
            List<List<ParameterSetOperation>> sets = q.getParametersList();
            int batchSize = execInfo.isBatch() ? Math.max(sets.size(), queryInfoList.size()) : 1;
            List<Object> params = sets.isEmpty() ? List.of() : values(sets.get(0));
            pl.sql().record(q.getQuery(), each, execInfo.isSuccess(), params, batchSize, TraceContext.current());
        }
    }

    private static List<Object> values(List<ParameterSetOperation> ops) {
        List<ParameterSetOperation> sorted = new ArrayList<>(ops);
        sorted.sort(Comparator.comparing(o -> String.valueOf(o.getArgs()[0]),
                Comparator.comparingInt((String s) -> s.length()).thenComparing(s -> s)));
        List<Object> out = new ArrayList<>(sorted.size());
        for (ParameterSetOperation op : sorted) {
            Object[] args = op.getArgs();
            boolean isNull = op.getMethod() != null && op.getMethod().getName().equals("setNull");
            out.add(isNull || args.length < 2 ? null : args[1]);
        }
        return out;
    }
}
