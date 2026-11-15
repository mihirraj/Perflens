package io.github.mihirraj.perflens.core.rules;

import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.PerfLensConfig;
import io.github.mihirraj.perflens.core.http.HttpRegistry;
import io.github.mihirraj.perflens.core.jfr.JfrProfiler;
import io.github.mihirraj.perflens.core.method.MethodRegistry;
import io.github.mihirraj.perflens.core.sql.SqlNormalizer;
import io.github.mihirraj.perflens.core.sql.SqlRegistry;
import io.github.mihirraj.perflens.core.trace.TraceRegistry;
import io.github.mihirraj.perflens.core.util.Exemplar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates all deterministic rules over the collected evidence. */
public final class RuleEngine {
    private final PerfLens pl;

    public RuleEngine(PerfLens pl) {
        this.pl = pl;
    }

    public List<Finding> evaluate() {
        List<Finding> out = new ArrayList<>();
        PerfLensConfig cfg = pl.config();
        Map<String, List<Exemplar>> methodExemplars = methodExemplarsByKey();

        slowQueries(cfg, out);
        nPlusOne(out);
        poolWait(cfg, out);
        hotMethods(cfg, out, methodExemplars);
        allocationHotspots(out, methodExemplars);
        slowMethods(cfg, out);
        slowHttp(cfg, out);
        httpErrors(cfg, out);
        waterfalls(out);
        slowEndpoints(cfg, out);
        locks(out);
        pinning(out);
        gc(out);

        out.sort(Comparator.comparingDouble(Finding::score).reversed());
        return out;
    }

    // ---------------- database ----------------

    private void slowQueries(PerfLensConfig cfg, List<Finding> out) {
        long thresholdUs = cfg.getSql().getSlowQueryThreshold().toNanos() / 1000;
        for (SqlRegistry.SqlStat s : pl.sql().stats()) {
            long p95, count, max, errors;
            Map<String, Long> callers;
            synchronized (s) {
                callers = new LinkedHashMap<>(s.callers);
                p95 = s.latency.percentile(95);
                count = s.latency.count();
                max = s.latency.max();
                errors = s.errors;
            }
            List<Exemplar> ex = s.slowest.snapshot();
            if (errors > 0) {
                out.add(finding("DB-QUERY-ERRORS", "DATABASE", Math.min(70, 30 + errors), "Query failed " + errors + " time(s)",
                        topCaller(callers), "Statement threw SQLException " + errors + " of " + count + " executions.",
                        List.of("Check the application log for the SQLException; failed statements often mean constraint violations, timeouts or deadlocks."),
                        ev("sql", s.fingerprint, "failures", errors, "executions", count), ex));
            }
            if (count == 0) continue;
            if (p95 < thresholdUs) {
                // typical execution is fast, but some executions were very slow (cold cache, lock wait, plan flip)
                long outlierUs = Math.max(5 * thresholdUs, 500_000);
                if (max >= outlierUs) {
                    double maxMs = max / 1000.0;
                    List<String> exP = ex.isEmpty() ? List.of() : castList(ex.get(0).details().get("params"));
                    List<String> sug = new ArrayList<>();
                    sug.add("Usually fast (p95 " + round(p95 / 1000.0) + " ms) but the slowest run took " + round(maxMs)
                            + " ms. Compare the slow executions' parameters below with typical ones: a specific value (large tenant, wide date range) or a cold cache / lock wait is the usual cause.");
                    sug.addAll(SqlAdvisor.adviseSlow(s.fingerprint, s.kind, s.paramNames, exP));
                    out.add(finding("DB-SLOW-QUERY-OUTLIER", "DATABASE", Math.min(60, 25 + maxMs / 100),
                            String.format(Locale.ROOT, "Occasionally very slow %s: max %.0f ms (p95 %.1f ms)", s.kind, maxMs, p95 / 1000.0),
                            topCaller(callers),
                            String.format(Locale.ROOT, "%d executions; most are fast, the worst took %.0f ms.", count, maxMs),
                            sug,
                            ev("sql", s.fingerprint, "kind", s.kind, "executions", count, "p95Ms", p95 / 1000.0, "maxMs", maxMs,
                                    "callers", callers),
                            ex));
                }
                continue;
            }
            double p95ms = p95 / 1000.0;
            double score = Math.min(100, 30 + p95ms / 20 + Math.log10(count + 1) * 10);
            List<String> exParams = ex.isEmpty() ? List.of() : castList(ex.get(0).details().get("params"));
            out.add(finding("DB-SLOW-QUERY", "DATABASE", score,
                    String.format(Locale.ROOT, "Slow %s: p95 %.0f ms over %d executions", s.kind, p95ms, count),
                    topCaller(callers),
                    String.format(Locale.ROOT, "p95 %.1f ms, max %.1f ms, total %.0f ms. The slowest real executions (with bind parameters) are attached.",
                            p95ms, max / 1000.0, totalMs(s.latency.sum())),
                    SqlAdvisor.adviseSlow(s.fingerprint, s.kind, s.paramNames, exParams),
                    ev("sql", s.fingerprint, "kind", s.kind, "executions", count, "p95Ms", p95ms, "maxMs", max / 1000.0,
                            "totalMs", totalMs(s.latency.sum()), "callers", callers),
                    ex));
        }
    }

    private void nPlusOne(List<Finding> out) {
        for (TraceRegistry.NPlusOneStat s : pl.traces().nPlusOne()) {
            double avg;
            long requests, max, micros;
            String caller;
            List<String> params;
            synchronized (s) {
                requests = s.requests;
                avg = s.totalRepeats / (double) Math.max(1, s.requests);
                max = s.maxRepeats;
                micros = s.totalMicros;
                caller = s.caller;
                params = s.sampleParams;
            }
            String kind = SqlNormalizer.kind(s.fingerprint);
            double score = Math.min(100, 40 + avg * 0.6 + Math.log10(requests + 1) * 8);
            out.add(finding("DB-N-PLUS-ONE", "DATABASE", score,
                    String.format(Locale.ROOT, "N+1: same %s runs ~%.0f times per call of %s", kind, avg, s.entrypoint),
                    caller,
                    String.format(Locale.ROOT, "Seen in %d request(s); worst request ran it %d times. Avg %.1f ms of SQL per request for this statement alone.",
                            requests, max, micros / 1000.0 / Math.max(1, requests)),
                    SqlAdvisor.adviseNPlusOne(kind, s.fingerprint),
                    ev("sql", s.fingerprint, "entrypoint", s.entrypoint, "requests", requests, "avgPerRequest", round(avg),
                            "maxPerRequest", max, "caller", caller, "sampleParams (first executions)", params),
                    List.of()));
        }
    }

    private void poolWait(PerfLensConfig cfg, List<Finding> out) {
        long p95, count, max;
        synchronized (pl.sql().poolWait()) {
            p95 = pl.sql().poolWait().percentile(95);
            count = pl.sql().poolWait().count();
            max = pl.sql().poolWait().max();
        }
        long thresholdUs = cfg.getSql().getPoolWaitThreshold().toNanos() / 1000;
        if (count < 20 || p95 < thresholdUs) return;
        double p95ms = p95 / 1000.0;
        out.add(finding("DB-POOL-WAIT", "DATABASE", Math.min(100, 35 + p95ms), String.format(Locale.ROOT,
                        "Threads wait for a DB connection (p95 %.0f ms)", p95ms), null,
                String.format(Locale.ROOT, "getConnection() p95 %.1f ms, max %.1f ms over %d acquisitions: the pool is often exhausted.", p95ms, max / 1000.0, count),
                List.of("Keep @Transactional methods short: don't call remote services or do heavy CPU work while a transaction (and its connection) is open.",
                        "Check the slow queries and N+1 findings first; slow statements hold connections longer.",
                        "If the database has headroom, raise spring.datasource.hikari.maximum-pool-size (default 10); also set spring.jpa.open-in-view=false so connections aren't held for the whole web request."),
                ev("p95Ms", p95ms, "maxMs", max / 1000.0, "acquisitions", count), pl.sql().poolWaitSlowest().snapshot()));
    }

    // ---------------- CPU / memory ----------------

    private void hotMethods(PerfLensConfig cfg, List<Finding> out, Map<String, List<Exemplar>> methodExemplars) {
        JfrProfiler prof = pl.profiler();
        long total = prof.totalSamples();
        if (total < cfg.getJfr().getMinSamples()) return;
        for (JfrProfiler.UserMethodProfile p : prof.userMethods()) {
            double share = p.samples * 100.0 / total;
            if (share < cfg.getJfr().getHotMethodPercent()) continue;
            Map<String, Double> shares = new HashMap<>();
            List<String> suggestions = HotMethodAdvisor.advise(p.callees, p.leaves, p.samples, shares);
            String line = topKey(p.lines);
            out.add(finding("CPU-HOT-METHOD", "CPU", Math.min(100, 20 + share * 2.5),
                    String.format(Locale.ROOT, "Hot method: %s uses %.1f%% of sampled CPU", shortName(p.method), share),
                    p.method + (line == null ? "" : ":" + line),
                    String.format(Locale.ROOT, "%d of %d CPU samples had this as the deepest application frame. Top library calls it makes and top lines are in evidence; slowest calls with their arguments are attached.",
                            p.samples, total),
                    suggestions,
                    ev("cpuSharePercent", round(share), "samples", p.samples, "topLines", topN(p.lines, 5, p.samples),
                            "topCallees", topN(p.callees, 6, p.samples), "topExecutingFrames", topN(p.leaves, 6, p.samples),
                            "sampledAllocationMB", round(p.allocBytes / 1e6)),
                    methodExemplars.getOrDefault(p.method, List.of())));
        }
    }

    private void allocationHotspots(List<Finding> out, Map<String, List<Exemplar>> methodExemplars) {
        JfrProfiler prof = pl.profiler();
        long total = prof.totalAllocBytes();
        if (total < 50L * 1024 * 1024) return;
        for (JfrProfiler.UserMethodProfile p : prof.userMethods()) {
            double share = p.allocBytes * 100.0 / total;
            if (share < 20) continue;
            out.add(finding("MEM-ALLOCATION-HOTSPOT", "MEMORY", Math.min(100, 20 + share * 1.5),
                    String.format(Locale.ROOT, "Allocation hotspot: %s allocates %.0f%% of sampled memory", shortName(p.method), share),
                    p.method, String.format(Locale.ROOT, "~%.0f MB allocated (sampled estimate).", p.allocBytes / 1e6),
                    allocationSuggestions(p),
                    ev("allocationSharePercent", round(share), "allocatedMB", round(p.allocBytes / 1e6),
                            "topAllocatedTypesMB", topNBytes(p.allocClasses, 6),
                            "topAllocatingCallsMB", topNBytes(p.allocCallees, 6)),
                    methodExemplars.getOrDefault(p.method, List.of())));
        }
    }

    /** Library-call patterns first (e.g. "new ObjectMapper per call"), then what kind of objects dominate. */
    private static List<String> allocationSuggestions(JfrProfiler.UserMethodProfile p) {
        List<String> out = new ArrayList<>();
        for (String s : HotMethodAdvisor.advise(p.allocCallees, Map.of(), p.allocBytes, null)) {
            if (!s.startsWith(HotMethodAdvisor.OWN_CODE)) out.add(s.replace("of this method's samples", "of this method's allocated bytes"));
        }
        out.addAll(HotMethodAdvisor.adviseAllocation(p.allocClasses));
        return out;
    }

    private void slowMethods(PerfLensConfig cfg, List<Finding> out) {
        long thresholdUs = cfg.getMethods().getSlowThreshold().toNanos() / 1000;
        record Candidate(MethodRegistry.MethodStat s, long p95, long count, long max, long sum) {}
        List<Candidate> slow = new ArrayList<>();
        for (MethodRegistry.MethodStat s : pl.methods().stats()) {
            synchronized (s) {
                long p95 = s.latency.percentile(95);
                if (s.latency.count() > 0 && p95 >= thresholdUs) {
                    slow.add(new Candidate(s, p95, s.latency.count(), s.latency.max(), s.latency.sum()));
                }
            }
        }
        // A thin wrapper (controller -> service) has the same call count and ~the same total time as the
        // method it delegates to. Report only the inner method and name the wrapper as its caller.
        Map<Candidate, String> wrappers = new HashMap<>();
        for (Candidate outer : slow) {
            for (Candidate inner : slow) {
                if (inner != outer && inner.count() == outer.count() && inner.sum() <= outer.sum()
                        && inner.sum() >= outer.sum() * 0.97 && !wrappers.containsKey(inner)) {
                    wrappers.put(outer, inner.s().key);
                    break;
                }
            }
        }
        Map<String, String> calledVia = new HashMap<>();
        wrappers.forEach((outer, innerKey) -> calledVia.merge(innerKey, shortName(outer.s().key), (a, b) -> a + ", " + b));

        for (Candidate c : slow) {
            if (wrappers.containsKey(c)) continue;
            MethodRegistry.MethodStat s = c.s();
            long p95 = c.p95(), count = c.count(), max = c.max();
            List<Exemplar> ex = s.slowest.snapshot();
            double sql = 0, http = 0, other = 0;
            for (Exemplar e : ex) {
                sql += num(e.details().get("sqlMs"));
                http += num(e.details().get("httpMs"));
                other += num(e.details().get("otherMs"));
            }
            double sum = Math.max(1e-9, sql + http + other);
            String dominant = sql / sum >= 0.5 ? "database" : http / sum >= 0.5 ? "outbound HTTP" : "own code / waiting";
            List<String> sug = new ArrayList<>();
            if (dominant.equals("database")) {
                sug.add(String.format(Locale.ROOT, "%.0f%% of the slowest calls' time is SQL. See the DB findings with entrypoint/caller in this method; reduce query count (N+1) or speed up the slow statements.", sql * 100 / sum));
            } else if (dominant.equals("outbound HTTP")) {
                sug.add(String.format(Locale.ROOT, "%.0f%% of the slowest calls' time is outbound HTTP. Cache idempotent responses (@Cacheable), call independent services in parallel, and set read timeouts.", http * 100 / sum));
            } else {
                sug.add("Most time is neither SQL nor instrumented HTTP: it is CPU in this code, lock waits, sleeps, or uninstrumented I/O (Redis, Kafka, files). Check the hot-method, lock and socket sections for this method.");
            }
            sug.add("Compare the arguments of the slowest calls: if they share a pattern (large ids list, a specific tenant, a wide date range), the cost scales with input size; paginate or bound the input.");
            double p95ms = p95 / 1000.0;
            out.add(finding("METHOD-SLOW", "METHOD", Math.min(100, 15 + p95ms / 30 + Math.log10(count + 1) * 5),
                    String.format(Locale.ROOT, "Slow method: %s p95 %.0f ms (mostly %s)", shortName(s.key), p95ms, dominant),
                    s.signature,
                    String.format(Locale.ROOT, "%d calls, p95 %.1f ms, max %.1f ms. Slowest calls with real arguments and their SQL/HTTP breakdown are attached.", count, p95ms, max / 1000.0),
                    sug,
                    ev("calls", count, "p95Ms", p95ms, "maxMs", max / 1000.0, "errors", s.errors,
                            "slowestBreakdownMs", ev("sql", round(sql), "http", round(http), "other", round(other)),
                            "calledVia", calledVia.get(s.key)),
                    ex));
        }
    }

    // ---------------- network ----------------

    private void slowHttp(PerfLensConfig cfg, List<Finding> out) {
        long thresholdUs = cfg.getHttp().getSlowThreshold().toNanos() / 1000;
        for (HttpRegistry.HttpStat s : pl.http().stats()) {
            long p95, count, max;
            synchronized (s) {
                p95 = s.latency.percentile(95);
                count = s.latency.count();
                max = s.latency.max();
            }
            if (count == 0 || p95 < thresholdUs) continue;
            double p95ms = p95 / 1000.0;
            out.add(finding("NET-SLOW-HTTP", "NETWORK", Math.min(100, 25 + p95ms / 40 + Math.log10(count + 1) * 8),
                    String.format(Locale.ROOT, "Slow outbound call: %s p95 %.0f ms", s.key, p95ms), null,
                    String.format(Locale.ROOT, "%d calls, p95 %.1f ms, max %.1f ms.", count, p95ms, max / 1000.0),
                    List.of("Set explicit connect/read timeouts (spring.http.client.connect-timeout / read-timeout, or on the RestClient/RestTemplate builder) so a slow dependency can't hang your threads.",
                            "Cache responses for idempotent GETs that don't change often (@Cacheable with a TTL).",
                            "If this call is independent of others in the same request, run them concurrently (CompletableFuture / virtual threads).",
                            "Compare the slowest URLs below: specific ids or query params being slow points at the downstream service, not the network."),
                    ev("calls", count, "p95Ms", p95ms, "maxMs", max / 1000.0, "statuses", new LinkedHashMap<>(s.statuses)),
                    s.slowest.snapshot()));
        }
    }

    private void httpErrors(PerfLensConfig cfg, List<Finding> out) {
        for (HttpRegistry.HttpStat s : pl.http().stats()) {
            long count, errors;
            Map<Integer, Long> statuses;
            synchronized (s) {
                count = s.latency.count();
                errors = s.errors;
                statuses = new LinkedHashMap<>(s.statuses);
            }
            double rate = errors * 100.0 / Math.max(1, count);
            if (count < 5 || rate < cfg.getHttp().getErrorRatePercent()) continue;
            out.add(finding("NET-HTTP-ERRORS", "NETWORK", Math.min(100, 30 + rate),
                    String.format(Locale.ROOT, "Outbound call failing: %s (%.0f%% errors)", s.key, rate), null,
                    errors + " of " + count + " calls failed (5xx, timeout or connection error).",
                    List.of("Add retries with backoff for idempotent calls (Spring Retry @Retryable or Resilience4j Retry), and a circuit breaker so a failing dependency fails fast.",
                            "Status 0 means no response at all (connection refused, DNS, timeout): check connectivity and timeouts."),
                    ev("calls", count, "errors", errors, "errorRatePercent", round(rate), "statuses", statuses),
                    s.slowest.snapshot()));
        }
    }

    private void waterfalls(List<Finding> out) {
        for (TraceRegistry.WaterfallStat s : pl.traces().waterfalls()) {
            double avgChain, avgSavable, avgReq;
            long requests, maxCalls;
            List<String> sample;
            synchronized (s) {
                requests = s.requests;
                avgChain = s.totalChainMicros / 1000.0 / Math.max(1, requests);
                avgSavable = s.totalSavableMicros / 1000.0 / Math.max(1, requests);
                avgReq = s.totalRequestMicros / 1000.0 / Math.max(1, requests);
                maxCalls = s.maxCalls;
                sample = s.sampleCalls;
            }
            out.add(finding("NET-HTTP-WATERFALL", "NETWORK", Math.min(100, 30 + avgSavable / 5),
                    String.format(Locale.ROOT, "%s makes %d outbound calls one after another", s.entrypoint, maxCalls), null,
                    String.format(Locale.ROOT, "Sequential calls take %.0f ms of a %.0f ms request on average. Running them in parallel could save ~%.0f ms per request, if they don't depend on each other.",
                            avgChain, avgReq, avgSavable),
                    List.of("If the calls are independent, start them concurrently: CompletableFuture.supplyAsync(..., executor) for each, then join(); on Java 21+ an Executors.newVirtualThreadPerTaskExecutor() is a cheap executor.",
                            "If one call needs another's result, only the dependent chain must stay sequential; parallelize the rest.",
                            "If the same data is fetched repeatedly, cache it or ask the downstream service for a batch endpoint."),
                    ev("entrypoint", s.entrypoint, "requests", requests, "avgSequentialMs", round(avgChain),
                            "avgRequestMs", round(avgReq), "estimatedSavingMs", round(avgSavable), "sampleCalls", sample),
                    List.of()));
        }
    }

    private void slowEndpoints(PerfLensConfig cfg, List<Finding> out) {
        long thresholdUs = cfg.getHttp().getSlowEndpointThreshold().toNanos() / 1000;
        for (TraceRegistry.EntrypointStat s : pl.traces().entrypoints()) {
            long p95, count;
            double sqlMs, httpMs, totalMs;
            synchronized (s) {
                p95 = s.latency.percentile(95);
                count = s.latency.count();
                sqlMs = s.sqlMicros / 1000.0 / Math.max(1, count);
                httpMs = s.httpMicros / 1000.0 / Math.max(1, count);
                totalMs = s.latency.mean() / 1000.0;
            }
            if (count == 0 || p95 < thresholdUs) continue;
            double otherMs = Math.max(0, totalMs - sqlMs - httpMs);
            String dominant = sqlMs >= httpMs && sqlMs >= otherMs ? "database" : httpMs >= otherMs ? "outbound HTTP" : "application code / waiting";
            double p95ms = p95 / 1000.0;
            out.add(finding("ENDPOINT-SLOW", "ENDPOINT", Math.min(100, 20 + p95ms / 50 + Math.log10(count + 1) * 6),
                    String.format(Locale.ROOT, "Slow entrypoint: %s p95 %.0f ms (mostly %s)", s.name, p95ms, dominant), null,
                    String.format(Locale.ROOT, "Average request: %.0f ms = %.0f ms SQL + %.0f ms HTTP + %.0f ms other.", totalMs, sqlMs, httpMs, otherMs),
                    List.of("Start with the " + dominant + " findings for this entrypoint; they share the same entrypoint name in their evidence."),
                    ev("requests", count, "p95Ms", p95ms, "avgMs", round(totalMs), "avgSqlMs", round(sqlMs),
                            "avgHttpMs", round(httpMs), "avgOtherMs", round(otherMs), "errors", s.errors),
                    s.slowest.snapshot()));
        }
    }

    // ---------------- JVM ----------------

    private void locks(List<Finding> out) {
        for (Map.Entry<String, JfrProfiler.WaitStat> e : pl.profiler().locks().entrySet()) {
            JfrProfiler.WaitStat s = e.getValue();
            double totalMs = s.totalNanos / 1e6, maxMs = s.maxNanos / 1e6;
            if (totalMs < 500 && maxMs < 100) continue;
            out.add(finding("LOCK-CONTENTION", "LOCK", Math.min(100, 25 + totalMs / 100),
                    "Lock contention on " + e.getKey(), afterAt(e.getKey()),
                    String.format(Locale.ROOT, "Threads blocked %d times entering this synchronized monitor, %.0f ms total, max %.0f ms.", s.count, totalMs, maxMs),
                    List.of("Shrink the synchronized block to the minimum shared-state update; never do I/O or remote calls while holding it.",
                            "Replace with a concurrent structure (ConcurrentHashMap.compute, LongAdder, AtomicReference) or a ReadWriteLock if reads dominate."),
                    ev("blockedCount", s.count, "totalBlockedMs", round(totalMs), "maxBlockedMs", round(maxMs)), List.of()));
        }
    }

    private void pinning(List<Finding> out) {
        boolean jdk24 = Runtime.version().feature() >= 24;
        for (Map.Entry<String, JfrProfiler.WaitStat> e : pl.profiler().pinned().entrySet()) {
            JfrProfiler.WaitStat s = e.getValue();
            double totalMs = s.totalNanos / 1e6, maxMs = s.maxNanos / 1e6;
            out.add(finding("VT-PINNED", "LOCK", Math.min(100, 30 + maxMs / 2),
                    "Virtual thread pinned at " + e.getKey(), e.getKey(),
                    String.format(Locale.ROOT, "Pinned %d times, %.0f ms total, max %.0f ms: the carrier thread could not run other virtual threads.", s.count, totalMs, maxMs),
                    jdk24 ? List.of("On JDK 24+ synchronized no longer pins. Remaining causes are blocking inside native code (JNI) or inside a class initializer / Object.wait(); move that blocking call out of the native/static-init path.")
                          : List.of("On JDK 21-23, blocking I/O inside synchronized pins the carrier thread. Replace synchronized with java.util.concurrent.locks.ReentrantLock in this code path, or upgrade to JDK 24+ where this pinning is gone."),
                    ev("pinnedCount", s.count, "totalPinnedMs", round(totalMs), "maxPinnedMs", round(maxMs)), List.of()));
        }
    }

    private void gc(List<Finding> out) {
        Map<String, Object> g = pl.profiler().gc();
        double pct = num(g.get("pausePercentOfWallTime"));
        double max = num(g.get("maxPauseMs"));
        if (pct < 5 && max < 500) return;
        out.add(finding("GC-PRESSURE", "MEMORY", Math.min(100, 30 + pct * 4 + max / 50),
                String.format(Locale.ROOT, "GC pauses: %.1f%% of time, max pause %.0f ms", pct, max), null,
                "The JVM spends noticeable time in stop-the-world garbage collection.",
                List.of("Check the allocation hotspot findings: reducing allocation is the most effective fix.",
                        "Make sure -Xmx leaves headroom (old gen consistently near full means a leak or an undersized heap); for low latency consider -XX:+UseZGC on JDK 21+."),
                g, List.of()));
    }

    // ---------------- helpers ----------------

    private Map<String, List<Exemplar>> methodExemplarsByKey() {
        Map<String, List<Exemplar>> m = new HashMap<>();
        for (MethodRegistry.MethodStat s : pl.methods().stats()) {
            m.computeIfAbsent(s.key, k -> new ArrayList<>()).addAll(s.slowest.snapshot());
        }
        m.values().forEach(l -> l.sort(Comparator.comparingDouble(Exemplar::durationMs).reversed()));
        return m;
    }

    private static Finding finding(String rule, String category, double score, String title, String location,
                                   String summary, List<String> suggestions, Map<String, Object> evidence,
                                   List<Exemplar> exemplars) {
        double s = round(Math.max(0, Math.min(100, score)));
        // Stable across refreshes and snapshots: built from what the finding is about, never from
        // the measured numbers in the title (those change every few seconds).
        String subject = location + "|" + evidence.get("sql") + "|" + evidence.get("entrypoint") + "|"
                + title.replaceAll("[0-9][0-9.,]*%?", "#");
        String id = rule + "-" + Integer.toHexString(subject.hashCode());
        return new Finding(id, rule, category, Finding.severityFor(s), s, title, location, summary, suggestions,
                evidence, exemplars);
    }

    private static Map<String, Object> ev(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String topCaller(Map<String, Long> callers) {
        return callers.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static <K> String topKey(Map<K, Long> m) {
        return m.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> String.valueOf(e.getKey())).orElse(null);
    }

    private static <K> Map<String, String> topN(Map<K, Long> m, int n, long denominator) {
        Map<String, String> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<K, Long>comparingByValue().reversed()).limit(n)
                .forEach(e -> out.put(String.valueOf(e.getKey()),
                        String.format(Locale.ROOT, "%.0f%%", e.getValue() * 100.0 / Math.max(1, denominator))));
        return out;
    }

    private static Map<String, Double> topNBytes(Map<String, Long> m, int n) {
        Map<String, Double> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(n)
                .forEach(e -> out.put(e.getKey(), round(e.getValue() / 1e6)));
        return out;
    }

    static String shortName(String fq) {
        int paren = fq.indexOf('(');
        String base = paren < 0 ? fq : fq.substring(0, paren);
        int lastDot = base.lastIndexOf('.');
        int prevDot = lastDot <= 0 ? -1 : base.lastIndexOf('.', lastDot - 1);
        return prevDot < 0 ? base : base.substring(prevDot + 1);
    }

    private static String afterAt(String key) {
        int i = key.indexOf(" @ ");
        return i < 0 ? null : key.substring(i + 3);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return o instanceof List<?> l ? (List<String>) l : List.of();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static double totalMs(long micros) {
        return round(micros / 1000.0);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
