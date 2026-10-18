package com.ledgercore.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {
    private static final Duration BASE = Duration.ofSeconds(5);
    private static final Duration MAX = Duration.ofMinutes(15);

    @Test
    void growsExponentially() {
        assertThat(Backoff.delay(1, BASE, MAX, 0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(Backoff.delay(2, BASE, MAX, 0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(Backoff.delay(3, BASE, MAX, 0)).isEqualTo(Duration.ofSeconds(20));
        assertThat(Backoff.delay(4, BASE, MAX, 0)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void isCappedAtTheMaximum_evenForHugeAttemptCounts() {
        assertThat(Backoff.delay(20, BASE, MAX, 0)).isEqualTo(MAX);
        assertThat(Backoff.delay(500, BASE, MAX, 0)).isEqualTo(MAX);
    }

    @Test
    void jitterAddsAtMostTwentyPercent() {
        Duration d = Backoff.delay(3, BASE, MAX, 1.0);
        assertThat(d).isEqualTo(Duration.ofSeconds(24));
        assertThat(Backoff.delay(3, BASE, MAX, 0.5)).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(24));
    }

    @Test
    void zeroBase_meansImmediateRetry() {
        assertThat(Backoff.delay(5, Duration.ZERO, Duration.ZERO, 0.7)).isEqualTo(Duration.ZERO);
    }
}
