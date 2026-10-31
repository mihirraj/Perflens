package dev.perflens.core.util;

/**
 * Fixed-size log-bucket latency histogram in microseconds (bucket growth 1.2x, ~10% error).
 * Not thread-safe: callers synchronize on the owning stat object.
 */
public final class Histogram {
    private static final double FACTOR = 1.2;
    private static final double LOG_FACTOR = Math.log(FACTOR);
    private static final int BUCKETS = 128;

    private final long[] counts = new long[BUCKETS];
    private long count;
    private long sum;
    private long max;

    public void record(long micros) {
        if (micros < 0) micros = 0;
        counts[index(micros)]++;
        count++;
        sum += micros;
        if (micros > max) max = micros;
    }

    private static int index(long micros) {
        if (micros <= 1) return 0;
        int i = (int) Math.ceil(Math.log(micros) / LOG_FACTOR);
        return Math.min(i, BUCKETS - 1);
    }

    private static long upperBound(int index) {
        return (long) Math.ceil(Math.pow(FACTOR, index));
    }

    /** Approximate percentile (0..100) in microseconds, never above the observed max. */
    public long percentile(double p) {
        if (count == 0) return 0;
        long target = (long) Math.ceil(count * p / 100.0);
        long seen = 0;
        for (int i = 0; i < BUCKETS; i++) {
            seen += counts[i];
            if (seen >= target) return Math.min(upperBound(i), max);
        }
        return max;
    }

    public void reset() {
        java.util.Arrays.fill(counts, 0);
        count = 0;
        sum = 0;
        max = 0;
    }

    public long count() { return count; }
    public long sum() { return sum; }
    public long max() { return max; }
    public long mean() { return count == 0 ? 0 : sum / count; }
}
