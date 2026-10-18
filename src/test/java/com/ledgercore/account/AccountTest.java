package com.ledgercore.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.common.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {
    private Account customer(long balance) {
        Account a = new Account("1000000001", UUID.randomUUID(), AccountType.CUSTOMER, "NGN", 1_000_000);
        if (balance > 0) a.credit(balance);
        return a;
    }

    @Test
    void creditAndDebit_adjustTheBalance() {
        Account a = customer(1_000);
        a.debit(300);
        a.credit(50);
        assertThat(a.getBalanceMinor()).isEqualTo(750);
    }

    @Test
    void customerAccount_cannotBeOverdrawn_andIsLeftUntouched() {
        Account a = customer(100);
        assertThatThrownBy(() -> a.debit(101))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(a.getBalanceMinor()).isEqualTo(100);
        a.debit(100);
        assertThat(a.getBalanceMinor()).isZero();
    }

    @Test
    void systemAccount_mayGoNegative_becauseItRepresentsFundsIssued() {
        Account cash = new Account("0000000001", null, AccountType.SYSTEM, "NGN", Long.MAX_VALUE);
        cash.debit(5_000);
        assertThat(cash.getBalanceMinor()).isEqualTo(-5_000);
    }

    @Test
    void nonPositiveAmounts_areRejected() {
        Account a = customer(100);
        assertThatThrownBy(() -> a.debit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.credit(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arithmeticOverflow_isDetected_notSilentlyWrapped() {
        Account a = customer(0);
        a.credit(Long.MAX_VALUE);
        assertThatThrownBy(() -> a.credit(1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void masksBeneficiaryNames() {
        assertThat(AccountService.mask("Adaeze Okafor")).isEqualTo("A***** O*****");
        assertThat(AccountService.mask("Al")).isEqualTo("A*");
    }
}
