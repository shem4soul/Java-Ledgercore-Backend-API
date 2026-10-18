package com.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgercore.common.BusinessException;
import com.ledgercore.ledger.AdminLedgerService;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The database itself refuses to corrupt the books, and reconciliation proves it. */
class LedgerIntegrityIT extends AbstractIT {
    @Autowired PlatformTransactionManager txManager;
    @Autowired AdminLedgerService ledgerAdmin;

    @Test
    void ledgerEntries_areAppendOnly() {
        customer(1_000);   // guarantees at least one entry exists
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_entries"))
                .hasMessageContaining("append-only");
    }

    @Test
    void auditLog_isAppendOnly() {
        customer(0);       // registration writes an audit row
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs"))
                .hasMessageContaining("append-only");
    }

    @Test
    void aOneSidedLedgerTransaction_cannotBeCommitted() {
        Customer c = customer(0);
        UUID tx = UUID.randomUUID();
        TransactionTemplate template = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> template.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO ledger_transactions (id, reference, type, status, amount_minor, currency) VALUES (?, ?, 'TRANSFER', 'POSTED', 100, 'NGN')",
                    tx, "TXN-" + tx.toString().replace("-", "").substring(0, 20));
            jdbc.update("INSERT INTO ledger_entries (id, transaction_id, account_id, direction, amount_minor, balance_after) VALUES (?, ?, ?, 'DEBIT', 100, 0)",
                    UUID.randomUUID(), tx, c.account().getId());
        })).hasStackTraceContaining("unbalanced");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", Integer.class, tx)).isZero();
    }

    @Test
    void aCustomerBalance_cannotBeForcedNegative() {
        Customer c = customer(1_000);
        assertThatThrownBy(() -> jdbc.update("UPDATE accounts SET balance_minor = -1 WHERE id = ?", c.account().getId()))
                .hasMessageContaining("ck_customer_not_overdrawn");
    }

    @Test
    void reconciliation_detectsTampering_andRecoversWhenFixed() {
        Customer c = customer(10_000);
        assertThat(reconciliation.run().healthy()).isTrue();

        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + 5 WHERE id = ?", c.account().getId());   // simulated tampering
        var bad = reconciliation.run();
        assertThat(bad.healthy()).isFalse();
        assertThat(bad.mismatches()).anySatisfy(m -> {
            assertThat(m.accountId()).isEqualTo(c.account().getId());
            assertThat(m.storedBalanceMinor() - m.ledgerBalanceMinor()).isEqualTo(5);
        });
        assertThat(bad.sumOfAllBalancesMinor()).as("global invariant is also violated").isEqualTo(5);

        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 5 WHERE id = ?", c.account().getId());
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    void reversal_restoresBalances_onlyOnce_andNeverEditsHistory() {
        Customer payer = customer(50_000);
        Customer payee = customer(0);
        TransferResponse t = send(payer, payee.account(), 20_000, "rev-" + UUID.randomUUID());

        TransferResponse reversal = ledgerAdmin.reverse(adminId(), t.transactionId(), "customer dispute");
        assertThat(balance(payer.account())).isEqualTo(50_000);
        assertThat(balance(payee.account())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ledger_transactions WHERE id = ?", String.class, t.transactionId())).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?", Integer.class, t.transactionId()))
                .as("original entries untouched").isEqualTo(2);

        assertThatThrownBy(() -> ledgerAdmin.reverse(adminId(), t.transactionId(), "again"))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("ALREADY_REVERSED");
        assertThatThrownBy(() -> ledgerAdmin.reverse(adminId(), reversal.transactionId(), "undo the undo"))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("CANNOT_REVERSE_REVERSAL");
        assertThat(reconciliation.run().healthy()).isTrue();
    }

    @Test
    void reversal_failsCleanly_ifTheRecipientHasAlreadySpentTheMoney() {
        Customer payer = customer(50_000);
        Customer payee = customer(0);
        Customer third = customer(0);
        TransferResponse t = send(payer, payee.account(), 20_000, "spent-" + UUID.randomUUID());
        send(payee, third.account(), 15_000, "spend-" + UUID.randomUUID());

        assertThatThrownBy(() -> ledgerAdmin.reverse(adminId(), t.transactionId(), "too late"))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT status FROM ledger_transactions WHERE id = ?", String.class, t.transactionId()))
                .as("original stays POSTED when the reversal cannot be applied").isEqualTo("POSTED");
        assertThat(balance(payee.account())).isEqualTo(5_000);
    }

    @Test
    void frozenAccount_cannotSend_orReceive() {
        Customer payer = customer(10_000);
        Customer payee = customer(0);
        accountService.setStatus(adminId(), payer.account().getId(), com.ledgercore.account.AccountStatus.FROZEN, "fraud review");
        assertThatThrownBy(() -> send(payer, payee.account(), 100, "frz-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        accountService.setStatus(adminId(), payer.account().getId(), com.ledgercore.account.AccountStatus.ACTIVE, "cleared");
        send(payer, payee.account(), 100, "frz-" + UUID.randomUUID());
        assertThat(balance(payee.account())).isEqualTo(100);
    }

    @Test
    void transferValidation_rulesAreEnforced() {
        Customer payer = customer(1_000);
        Customer other = customer(0);
        assertThatThrownBy(() -> send(payer, payer.account(), 100, "self-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("SAME_ACCOUNT");
        // sending from somebody else's account looks exactly like a missing account
        Customer thief = customer(0);
        assertThatThrownBy(() -> send(new Customer(thief.user(), payer.account()), other.account(), 100, "own-" + UUID.randomUUID()))
                .isInstanceOf(BusinessException.class).extracting(e -> ((BusinessException) e).code()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(balance(payer.account())).isEqualTo(1_000);
    }
}
