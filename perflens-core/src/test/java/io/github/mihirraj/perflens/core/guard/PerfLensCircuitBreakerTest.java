package io.github.mihirraj.perflens.core.guard;

import io.github.mihirraj.perflens.core.PerfLens;
import io.github.mihirraj.perflens.core.PerfLensConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PerfLensCircuitBreakerTest {

    @Test
    void pressurePausesProfilerAndCollectionThenRecovers() throws Exception {
        PerfLensConfig cfg = new PerfLensConfig();
        cfg.setBasePackages(List.of("io.github.mihirraj.perflens.test"));
        cfg.getCircuitBreaker().setCheckInterval(Duration.ofMillis(100));
        AtomicReference<Guard.Reading> reading = new AtomicReference<>(new Guard.Reading(95, 0));

        try (PerfLens pl = new PerfLens(cfg)) {
            pl.start(reading::get);
            waitUntil(() -> !pl.isCollecting());
            assertThat(pl.profiler().isRunning()).isFalse();
            assertThat(pl.guardStatus()).containsEntry("state", "PAUSED");

            reading.set(new Guard.Reading(40, 0));
            waitUntil(pl::isCollecting);
            assertThat(pl.guardStatus()).containsEntry("state", "ACTIVE");
            assertThat(pl.profiler().isRunning()).isTrue();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not reached in 5 s");
            Thread.sleep(20);
        }
    }
}
