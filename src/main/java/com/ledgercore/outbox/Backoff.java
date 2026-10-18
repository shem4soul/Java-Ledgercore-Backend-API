package com.ledgercore.outbox;

import java.time.Duration;

/** Exponential backoff: base * 2^(attempt-1), capped at max, plus up to 20% jitter to avoid retry stampedes. */
public final class Backoff {
    private Backoff() {}

    public static Duration delay(int attempt, Duration base, Duration max, double jitterFraction01) {
        if (attempt < 1) attempt = 1;
        long baseMs = base.toMillis();
        long maxMs = max.toMillis();
        long exp = attempt - 1 >= 30 ? Long.MAX_VALUE / 4 : (1L << (attempt - 1));
        long raw = (baseMs != 0 && exp > maxMs / baseMs) ? maxMs : baseMs * exp;
        long capped = Math.min(raw, maxMs);
        long jitter = (long) (capped * 0.2 * Math.max(0.0, Math.min(1.0, jitterFraction01)));
        return Duration.ofMillis(capped + jitter);
    }
}
