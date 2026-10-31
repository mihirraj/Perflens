package dev.perflens.core.trace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that happened inside one logical unit of work (an HTTP request, a @Scheduled run,
 * a listener invocation). Confined to one thread, so no synchronization.
 */
public final class TraceWindow {
    public static final int MAX_HTTP_CALLS = 200;
    public static final int MAX_FINGERPRINTS = 500;
    public static final int SAMPLE_PARAMS = 5;

    public record HttpCall(long startNanos, long endNanos, String target, int status) {}

    /** Per-statement counter for N+1 detection. */
    public static final class SqlRepeat {
        public int count;
        public long micros;
        public String caller;
        public final List<String> sampleParams = new ArrayList<>(SAMPLE_PARAMS);
    }

    private String entrypoint;
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
    public SqlRepeat onSql(String fingerprint, long micros) {
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

    /** Synchronized: reactive clients (WebClient) may complete calls on other threads, even concurrently. */
    public synchronized void onHttp(HttpCall call) {
        httpCount++;
        httpMicros += (call.endNanos() - call.startNanos()) / 1000;
        if (httpCalls.size() < MAX_HTTP_CALLS) httpCalls.add(call);
    }

    public String entrypoint() { return entrypoint; }
    public void entrypoint(String name) { this.entrypoint = name; }
    public long startNanos() { return startNanos; }
    public Map<String, SqlRepeat> sqlRepeats() { return sqlRepeats; }
    public synchronized List<HttpCall> httpCalls() { return new ArrayList<>(httpCalls); }
    public int sqlCount() { return sqlCount; }
    public long sqlMicros() { return sqlMicros; }
    public synchronized int httpCount() { return httpCount; }
    public synchronized long httpMicros() { return httpMicros; }
}
