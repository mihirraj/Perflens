package io.github.mihirraj.perflens.core.trace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that happened inside one logical unit of work (an HTTP request, a @Scheduled run,
 * a listener invocation).
 *
 * <p>Synchronized: on a servlet thread the window is confined to one thread and the locks are
 * uncontended, but one WebFlux request can run several queries or WebClient calls in parallel and
 * complete them on different threads.
 */
public final class TraceWindow {
    public static final int MAX_HTTP_CALLS = 200;
    public static final int MAX_FINGERPRINTS = 500;
    public static final int SAMPLE_PARAMS = 5;

    public record HttpCall(long startNanos, long endNanos, String target, int status) {}

    /** Per-statement counter for N+1 detection. Mutated only through the owning window. */
    public static final class SqlRepeat {
        private int count;
        private long micros;
        private String caller;
        private final List<String> sampleParams = new ArrayList<>(SAMPLE_PARAMS);
    }

    /** Immutable copy of one statement's repeats, taken when the window completes. */
    public record Repeat(String fingerprint, int count, long micros, String caller, List<String> sampleParams) {}

    private volatile String entrypoint;
    private final long startNanos = System.nanoTime();
    private final Map<String, SqlRepeat> sqlRepeats = new HashMap<>();
    private final List<HttpCall> httpCalls = new ArrayList<>();
    private int sqlCount;
    private long sqlMicros;
    private int httpCount;
    private long httpMicros;

    public TraceWindow(String entrypoint) {
        this.entrypoint = entrypoint;
    }

    /** Returns the repeat counter (null if the per-window cap is reached). */
    public synchronized SqlRepeat onSql(String fingerprint, long micros) {
        sqlCount++;
        sqlMicros += micros;
        SqlRepeat r = sqlRepeats.get(fingerprint);
        if (r == null) {
            if (sqlRepeats.size() >= MAX_FINGERPRINTS) return null;
            r = new SqlRepeat();
            sqlRepeats.put(fingerprint, r);
        }
        r.count++;
        r.micros += micros;
        return r;
    }

    /** True once the statement repeated {@code threshold} times and its caller is not known yet. */
    public synchronized boolean needsCaller(SqlRepeat r, int threshold) {
        return r.caller == null && r.count >= threshold;
    }

    public synchronized void caller(SqlRepeat r, String caller) {
        if (r.caller == null) r.caller = caller;
    }

    public synchronized boolean needsSampleParams(SqlRepeat r) {
        return r.sampleParams.size() < SAMPLE_PARAMS;
    }

    public synchronized void addSampleParams(SqlRepeat r, String params) {
        if (r.sampleParams.size() < SAMPLE_PARAMS) r.sampleParams.add(params);
    }

    /** Statements executed at least {@code minCount} times in this window. */
    public synchronized List<Repeat> repeatsAtLeast(int minCount) {
        List<Repeat> out = new ArrayList<>();
        for (Map.Entry<String, SqlRepeat> e : sqlRepeats.entrySet()) {
            SqlRepeat r = e.getValue();
            if (r.count >= minCount) out.add(new Repeat(e.getKey(), r.count, r.micros, r.caller, List.copyOf(r.sampleParams)));
        }
        return out;
    }

    public synchronized void onHttp(HttpCall call) {
        httpCount++;
        httpMicros += (call.endNanos() - call.startNanos()) / 1000;
        if (httpCalls.size() < MAX_HTTP_CALLS) httpCalls.add(call);
    }

    public String entrypoint() { return entrypoint; }
    public void entrypoint(String name) { this.entrypoint = name; }
    public long startNanos() { return startNanos; }
    public synchronized List<HttpCall> httpCalls() { return new ArrayList<>(httpCalls); }
    public synchronized int sqlCount() { return sqlCount; }
    public synchronized long sqlMicros() { return sqlMicros; }
    public synchronized int httpCount() { return httpCount; }
    public synchronized long httpMicros() { return httpMicros; }
}
