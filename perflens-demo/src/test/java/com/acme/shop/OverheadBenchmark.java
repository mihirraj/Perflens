package com.acme.shop;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures PerfLens overhead on the demo app: identical workload with PerfLens off and on,
 * alternating (off, on, off, on) so JIT warm-up does not favour either mode.
 *
 * <p>Not part of the normal build. Run with:
 * {@code mvnw -pl perflens-demo test -Dtest=OverheadBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbenchmark=true}
 */
@Tag("benchmark")
class OverheadBenchmark {
    private static final int THREADS = 4;
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final Duration WARMUP = Duration.ofSeconds(Long.getLong("bench.warmup", 15));
    private static final Duration MEASURE = Duration.ofSeconds(Long.getLong("bench.measure", 30));
    /** CPU- and DB-bound endpoints only: the sleep-based mock services would hide any overhead. */
    private static final List<String> WORKLOAD = List.of(
            "/orders/summary?limit=20", "/pricing/quote?customerId=3&items=60", "/customers/search?q=user4",
            "/orders/summary?limit=5");

    record Result(boolean perflens, long requests, double seconds, double cpuMsPerRequest, double p50Ms, double p99Ms) {
        double throughput() { return requests / seconds; }
    }

    @Test
    void measure() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("benchmark"), "run with -Dbenchmark=true");
        List<Result> results = new ArrayList<>();
        int run = 0;
        for (boolean on : new boolean[]{false, true, false, true}) {
            results.add(runOnce(on, run++));
        }
        System.out.println();
        System.out.println("mode      req/s    CPU ms/req   p50 ms   p99 ms");
        for (Result r : results) {
            System.out.printf(Locale.ROOT, "%-8s %7.1f %11.3f %8.2f %8.2f%n", r.perflens() ? "on" : "off",
                    r.throughput(), r.cpuMsPerRequest(), r.p50Ms(), r.p99Ms());
        }
        Result off = avg(results, false), on = avg(results, true);
        System.out.printf(Locale.ROOT, "%nOVERHEAD: throughput %+.1f%%, CPU per request %+.1f%%, p50 %+.2f ms, p99 %+.2f ms%n",
                (on.throughput() / off.throughput() - 1) * 100, (on.cpuMsPerRequest() / off.cpuMsPerRequest() - 1) * 100,
                on.p50Ms() - off.p50Ms(), on.p99Ms() - off.p99Ms());
    }

    private static Result avg(List<Result> rs, boolean on) {
        List<Result> m = rs.stream().filter(r -> r.perflens() == on).toList();
        return new Result(on, m.stream().mapToLong(Result::requests).sum(), m.stream().mapToDouble(Result::seconds).sum(),
                m.stream().mapToDouble(Result::cpuMsPerRequest).average().orElse(0),
                m.stream().mapToDouble(Result::p50Ms).average().orElse(0), m.stream().mapToDouble(Result::p99Ms).average().orElse(0));
    }

    private Result runOnce(boolean perflens, int run) throws Exception {
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ShopApplication.class).properties(
                "server.port=0", "demo.load.enabled=false", "perflens.enabled=" + perflens,
                "perflens.storage.enabled=false", "logging.level.root=WARN",
                "spring.datasource.url=jdbc:h2:mem:bench" + run + ";DB_CLOSE_DELAY=-1")
                // extra PerfLens settings for the 'on' runs, ';'-separated,
                // e.g. -Dbench.on="perflens.jfr.enabled=false;perflens.sql.enabled=false"
                .properties(perflens ? System.getProperty("bench.on", "perflens.enabled=true").split(";") : new String[0])
                .run();
        try {
            String base = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            drive(http, base, WARMUP, null);
            List<long[]> latencies = new ArrayList<>();
            long cpu0 = processCpuNanos();
            long t0 = System.nanoTime();
            long requests = drive(http, base, MEASURE, latencies);
            double seconds = (System.nanoTime() - t0) / 1e9;
            double cpuMs = (processCpuNanos() - cpu0) / 1e6;
            long[] all = latencies.stream().flatMapToLong(Arrays::stream).filter(v -> v > 0).sorted().toArray();
            return new Result(perflens, requests, seconds, cpuMs / Math.max(1, requests),
                    all[(int) (all.length * 0.50)] / 1e6, all[(int) Math.min(all.length - 1, all.length * 0.99)] / 1e6);
        } finally {
            ctx.close();
        }
    }

    /** Closed-loop load from THREADS clients; returns requests completed. */
    private long drive(HttpClient http, String base, Duration d, List<long[]> latencies) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong count = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            long[] lat = new long[latencies == null ? 1 : 500_000];
            if (latencies != null) latencies.add(lat);
            Thread th = new Thread(() -> {
                int i = 0;
                while (!stop.get()) {
                    String path = WORKLOAD.get(i % WORKLOAD.size());
                    long s = System.nanoTime();
                    try {
                        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() >= 300) {
                            int n = (int) FAILURES.incrementAndGet();
                            if (n <= 3) System.out.println("NON-2xx " + resp.statusCode() + " " + path + ": " + resp.body().substring(0, Math.min(200, resp.body().length())));
                        }
                    } catch (Exception e) {
                        FAILURES.incrementAndGet();
                        continue;
                    }
                    if (i < lat.length) lat[i] = System.nanoTime() - s;
                    i++;
                    count.incrementAndGet();
                }
            });
            th.start();
            threads.add(th);
        }
        Thread.sleep(d.toMillis());
        stop.set(true);
        for (Thread th : threads) th.join();
        return count.get();
    }

    private static long processCpuNanos() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }
}
