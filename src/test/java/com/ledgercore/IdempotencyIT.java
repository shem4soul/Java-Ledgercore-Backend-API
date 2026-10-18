package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.common.BusinessException;
import com.ledgercore.idempotency.IdempotencyService.Outcome;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Duplicate-payment protection: a repeated request must never debit twice. */
class IdempotencyIT extends AbstractIT {

    @Test
    void twentyConcurrentRequestsWithTheSameKey_debitExactlyOnce() throws Exception {
        Customer payer = customer(100_000);
        Customer payee = customer(0);
        String key = "dup-" + UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Outcome<TransferResponse>>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) fs.add(pool.submit(() -> sendOutcome(payer, payee.account(), 5_000, key)));
        List<Outcome<TransferResponse>> outcomes = new ArrayList<>();
        for (Future<Outcome<TransferResponse>> f : fs) outcomes.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        assertThat(outcomes.stream().filter(o -> !o.replayed()).count()).as("exactly one execution").isEqualTo(1);
        assertThat(outcomes.stream().filter(Outcome::replayed).count()).isEqualTo(19);
        assertThat(outcomes.stream().map(o -> o.body().transactionId()).distinct().count()).as("all callers see the same transaction").isEqualTo(1);
        assertThat(balance(payer.account())).isEqualTo(95_000);
        assertThat(balance(payee.account())).isEqualTo(5_000);
    }

    @Test
    void sequentialReplay_returnsOriginalResponse_withoutMovingMoneyAgain() {
        Customer payer = customer(50_000);
        Customer payee = customer(0);
        String key = "seq-" + UUID.randomUUID();

        Outcome<TransferResponse> first = sendOutcome(payer, payee.account(), 1_000, key);
        Outcome<TransferResponse> second = sendOutcome(payer, payee.account(), 1_000, key);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(balance(payer.account())).isEqualTo(49_000);
    }

    @Test
    void sameKeyWithDifferentPayload_isRejected() {
        Customer payer = customer(50_000);
        Customer payee = customer(0);
        String key = "reuse-" + UUID.randomUUID();
        send(payer, payee.account(), 1_000, key);

        assertThatThrownBy(() -> send(payer, payee.account(), 2_000, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(payer.account())).as("the conflicting request moved no money").isEqualTo(49_000);
    }

    @Test
    void aFailedAttempt_leavesNoKeyBehind_soTheSameKeyCanBeRetriedSafely() {
        Customer payer = customer(1_000);
        Customer payee = customer(0);
        String key = "retry-" + UUID.randomUUID();

        assertThatThrownBy(() -> send(payer, payee.account(), 5_000, key))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
        Integer leftover = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                Integer.class, payer.user().id(), key);
        assertThat(leftover).as("rolled back together with the failed transfer").isZero();

        adminLedger.deposit(adminId(), payer.account().getId(), 10_000, "top up");
        Outcome<TransferResponse> retry = sendOutcome(payer, payee.account(), 5_000, key);
        assertThat(retry.replayed()).isFalse();
        assertThat(balance(payer.account())).isEqualTo(6_000);
    }

    @Test
    void sameKeyFromDifferentUsers_areIndependent() {
        Customer a = customer(10_000);
        Customer b = customer(10_000);
        Customer sink = customer(0);
        String key = "shared-" + UUID.randomUUID();
        assertThat(sendOutcome(a, sink.account(), 100, key).replayed()).isFalse();
        assertThat(sendOutcome(b, sink.account(), 100, key).replayed()).isFalse();
        assertThat(balance(sink.account())).isEqualTo(200);
    }

    @Test
    void malformedKey_isRejected() {
        Customer payer = customer(1_000);
        Customer payee = customer(0);
        assertThatThrownBy(() -> send(payer, payee.account(), 100, "short"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }
}
