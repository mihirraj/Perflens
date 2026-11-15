package io.github.mihirraj.perflens.core.guard;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardTest {
    private final List<String> events = new ArrayList<>();
    private final Guard guard = new Guard(new Guard.Target() {
        @Override
        public void pauseCollection(String reason) {
            events.add("pause");
        }

        @Override
        public void resumeCollection() {
            events.add("resume");
        }
    }, () -> null, 85, 10, Duration.ofSeconds(5));

    private void feed(double heap, double gc, int times) {
        for (int i = 0; i < times; i++) guard.evaluate(new Guard.Reading(heap, gc));
    }

    @Test
    void singleSpikeDoesNotTrip() {
        feed(95, 2, 1);
        feed(50, 2, 1);
        feed(95, 2, 1);
        assertThat(guard.state()).isEqualTo(Guard.State.ACTIVE);
        assertThat(events).isEmpty();
    }

    @Test
    void sustainedHeapPressurePausesThenResumesAfterCalmPeriod() {
        feed(90, 1, Guard.TRIP_AFTER);
        assertThat(guard.state()).isEqualTo(Guard.State.PAUSED);
        assertThat(guard.status().get("reason").toString()).contains("heap after GC 90%");

        // still above the resume margin (85 - 15 = 70): stays paused
        feed(75, 1, 20);
        assertThat(guard.state()).isEqualTo(Guard.State.PAUSED);

        // calm, but interrupted once: the streak restarts
        feed(50, 1, Guard.RESUME_AFTER - 1);
        feed(80, 1, 1);
        feed(50, 1, Guard.RESUME_AFTER - 1);
        assertThat(guard.state()).isEqualTo(Guard.State.PAUSED);
        feed(50, 1, 1);
        assertThat(guard.state()).isEqualTo(Guard.State.ACTIVE);
        assertThat(events).containsExactly("pause", "resume");
        assertThat(guard.status().get("trips")).isEqualTo(1);
    }

    @Test
    void gcThrashingTripsEvenWithModerateHeap() {
        feed(60, 25, Guard.TRIP_AFTER);
        assertThat(guard.state()).isEqualTo(Guard.State.PAUSED);
    }

    @Test
    void jvmReaderProducesSaneValues() {
        Guard.Reading r = Guard.jvmReader().get();
        assertThat(r.heapAfterGcPercent()).isBetween(0.0, 100.0);
        assertThat(r.gcTimePercent()).isBetween(0.0, 100.0);
    }
}
