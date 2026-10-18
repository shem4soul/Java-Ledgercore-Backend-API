package com.ledgercore.ledger;

import org.springframework.stereotype.Component;

/**
 * Test seam for failure-recovery tests: lets a test throw between the debit and the credit to prove the whole posting
 * rolls back. Nothing sets this in production, so it is a no-op.
 */
@Component
public class LedgerHooks {
    private volatile Runnable afterDebit;

    public void setAfterDebit(Runnable r) { this.afterDebit = r; }

    public void clear() { this.afterDebit = null; }

    void afterDebit() {
        Runnable r = afterDebit;
        if (r != null) r.run();
    }
}
