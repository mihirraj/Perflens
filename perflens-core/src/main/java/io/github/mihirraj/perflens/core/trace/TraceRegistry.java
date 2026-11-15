package io.github.mihirraj.perflens.core.trace;

import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.util.Exemplar;
import io.github.mihirraj.perflens.core.util.Histogram;
import io.github.mihirraj.perflens.core.util.TopK;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Analyses each finished {@link TraceWindow}: per-entrypoint time breakdown (DB / HTTP / other),
 * N+1 statement repeats, and sequential outbound-HTTP waterfalls.
 */
public final class TraceRegistry {
    private static final int MAX_ENTRYPOINTS = 500;
    private static final int MAX_NPLUSONE = 1_000;

    public static final class EntrypointStat {
        public final String name;
        public final Histogram latency = new Histogram();
        public final TopK slowest;
        public long errors;
        public long sqlCount;
        public long sqlMicros;
        public long httpCount;
        public long httpMicros;

        EntrypointStat(String name, int exemplars) {
            this.name = name;
            this.slowest = new TopK(exemplars);
        }
    }

    public static final class NPlusOneStat {
        public final String entrypoint;
        public final String fingerprint;
        public long requests;
        public long totalRepeats;
        public long maxRepeats;
        public long totalMicros;
        public String caller;
        public List<String> sampleParams = List.of();

        NPlusOneStat(String entrypoint, String fingerprint) {
            this.entrypoint = entrypoint;
            this.fingerprint = fingerprint;
        }
    }

    public static final class WaterfallStat {
        public final String entrypoint;
        public long requests;
        public long maxCalls;
        public long totalChainMicros;
        public long totalSavableMicros;
        public long totalRequestMicros;
        public List<String> sampleCalls = List.of();

        WaterfallStat(String entrypoint) {
            this.entrypoint = entrypoint;
        }
    }

    private final PerfLensConfig config;
    private final Map<String, EntrypointStat> entrypoints = new ConcurrentHashMap<>();
    private final Map<String, NPlusOneStat> nPlusOne = new ConcurrentHashMap<>();
    private final Map<String, WaterfallStat> waterfalls = new ConcurrentHashMap<>();

    public TraceRegistry(PerfLensConfig config) {
        this.config = config;
    }

    public void complete(TraceWindow w, boolean error) {
        long micros = (System.nanoTime() - w.startNanos()) / 1000;
        String name = w.entrypoint();
        EntrypointStat ep = entrypoints.get(name);
        if (ep == null && entrypoints.size() < MAX_ENTRYPOINTS) {
            ep = entrypoints.computeIfAbsent(name, k -> new EntrypointStat(k, config.getCapture().getExemplarsPerItem()));
        }
        if (ep != null) {
            synchronized (ep) {
                ep.latency.record(micros);
                if (error) ep.errors++;
                ep.sqlCount += w.sqlCount();
                ep.sqlMicros += w.sqlMicros();
                ep.httpCount += w.httpCount();
                ep.httpMicros += w.httpMicros();
            }
            double ms = micros / 1000.0;
            if (ms >= 1.0 && ep.slowest.accepts(ms)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("sqlQueries", w.sqlCount());
                d.put("sqlMs", w.sqlMicros() / 1000.0);
                d.put("httpCalls", w.httpCount());
                d.put("httpMs", w.httpMicros() / 1000.0);
                d.put("otherMs", Math.max(0, ms - w.sqlMicros() / 1000.0 - w.httpMicros() / 1000.0));
                d.put("thread", Thread.currentThread().getName());
                if (error) d.put("failed", true);
                ep.slowest.add(new Exemplar(ms, System.currentTimeMillis(), d));
            }
        }
        detectNPlusOne(w, name);
        detectWaterfall(w, name, micros);
    }

    private void detectNPlusOne(TraceWindow w, String entrypoint) {
        int threshold = config.getSql().getNplusoneThreshold();
        for (TraceWindow.Repeat r : w.repeatsAtLeast(threshold)) {
            String key = entrypoint + '\u0000' + r.fingerprint();
            NPlusOneStat s = nPlusOne.get(key);
            if (s == null) {
                if (nPlusOne.size() >= MAX_NPLUSONE) continue;
                s = nPlusOne.computeIfAbsent(key, k -> new NPlusOneStat(entrypoint, r.fingerprint()));
            }
            synchronized (s) {
                s.requests++;
                s.totalRepeats += r.count();
                s.totalMicros += r.micros();
                if (r.count() >= s.maxRepeats) {
                    s.maxRepeats = r.count();
                    s.caller = r.caller();
                    s.sampleParams = r.sampleParams();
                }
            }
        }
    }

    private void detectWaterfall(TraceWindow w, String entrypoint, long requestMicros) {
        if (w.httpCount() < config.getHttp().getWaterfallMinCalls()) return;
        List<TraceWindow.HttpCall> calls = new ArrayList<>(w.httpCalls());
        if (calls.size() < config.getHttp().getWaterfallMinCalls()) return;
        calls.sort(Comparator.comparingLong(TraceWindow.HttpCall::startNanos));

        List<TraceWindow.HttpCall> best = List.of();
        List<TraceWindow.HttpCall> run = new ArrayList<>();
        for (TraceWindow.HttpCall c : calls) {
            if (!run.isEmpty() && c.startNanos() < run.get(run.size() - 1).endNanos()) {
                // overlaps the previous call: already parallel, the sequential run ends here
                if (run.size() > best.size()) best = List.copyOf(run);
                run.clear();
            }
            run.add(c);
        }
        if (run.size() > best.size()) best = List.copyOf(run);
        if (best.size() < config.getHttp().getWaterfallMinCalls()) return;

        long chain = 0;
        long longest = 0;
        for (TraceWindow.HttpCall c : best) {
            long d = (c.endNanos() - c.startNanos()) / 1000;
            chain += d;
            longest = Math.max(longest, d);
        }
        boolean significant = chain >= requestMicros / 2 || chain >= 200_000;
        if (!significant) return;

        WaterfallStat s = waterfalls.computeIfAbsent(entrypoint, WaterfallStat::new);
        synchronized (s) {
            s.requests++;
            s.totalChainMicros += chain;
            s.totalSavableMicros += chain - longest;
            s.totalRequestMicros += requestMicros;
            if (best.size() >= s.maxCalls) {
                s.maxCalls = best.size();
                List<String> sample = new ArrayList<>();
                for (TraceWindow.HttpCall c : best) {
                    sample.add(c.target() + " (" + (c.endNanos() - c.startNanos()) / 1_000_000 + " ms, status " + c.status() + ")");
                }
                s.sampleCalls = sample;
            }
        }
    }

    public Collection<EntrypointStat> entrypoints() { return entrypoints.values(); }
    public Collection<NPlusOneStat> nPlusOne() { return nPlusOne.values(); }
    public Collection<WaterfallStat> waterfalls() { return waterfalls.values(); }

    public void reset() {
        entrypoints.clear();
        nPlusOne.clear();
        waterfalls.clear();
    }
}
