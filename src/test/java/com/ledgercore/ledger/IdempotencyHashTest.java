package com.ledgercore.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgercore.idempotency.IdempotencyService;
import org.junit.jupiter.api.Test;

class IdempotencyHashTest {
    @Test
    void sameInputs_sameHash_anyChange_differentHash() {
        String a = IdempotencyService.hash("acc", "1234567890", "1000", "NGN", "rent");
        assertThat(IdempotencyService.hash("acc", "1234567890", "1000", "NGN", "rent")).isEqualTo(a);
        assertThat(IdempotencyService.hash("acc", "1234567890", "1001", "NGN", "rent")).isNotEqualTo(a);
        assertThat(IdempotencyService.hash("acc", "1234567890", "1000", "USD", "rent")).isNotEqualTo(a);
        assertThat(a).hasSize(64);
    }
}
