package com.ledgercore.ledger;

import com.ledgercore.account.AccountRef;
import com.ledgercore.account.AccountRepository;
import com.ledgercore.account.AccountType;
import com.ledgercore.audit.AuditService;
import com.ledgercore.common.BusinessException;
import com.ledgercore.common.Money;
import com.ledgercore.ledger.TransferDtos.TransferRequest;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.user.User;
import com.ledgercore.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final AccountRepository accounts;
    private final UserRepository users;
    private final LedgerService ledger;
    private final AuditService audit;
    private final MeterRegistry metrics;

    public TransferService(AccountRepository accounts, UserRepository users, LedgerService ledger, AuditService audit, MeterRegistry metrics) {
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.audit = audit;
        this.metrics = metrics;
    }

    /** Runs inside the idempotency transaction (REQUIRED joins it). Authorisation is checked before any lock is taken. */
    @Transactional
    public TransferResponse transfer(AuthenticatedUser actor, TransferRequest req) {
        try {
            if (!Money.isSupported(req.currency())) {
                throw BusinessException.badRequest("UNSUPPORTED_CURRENCY", "Unsupported currency");
            }
            // tokens live for minutes, but a disabled user must not be able to move money even within that window
            users.findById(actor.id()).filter(User::isEnabled)
                    .orElseThrow(() -> BusinessException.forbidden("USER_DISABLED", "User is disabled"));

            AccountRef from = accounts.findRefById(req.fromAccountId())
                    .filter(a -> actor.id().equals(a.getOwnerId()))      // not yours == does not exist (no enumeration)
                    .orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "Source account not found"));
            AccountRef to = accounts.findRefByAccountNumber(req.toAccountNumber())
                    .filter(a -> a.getType() == AccountType.CUSTOMER)
                    .orElseThrow(() -> BusinessException.notFound("BENEFICIARY_NOT_FOUND", "Beneficiary account not found"));

            PostResult r = ledger.post(new PostCommand(TransactionType.TRANSFER, from.getId(), to.getId(), req.amountMinor(),
                    req.currency(), req.description(), actor.id(), null, true, false));

            audit.record(actor.id(), "TRANSFER_POSTED", "LEDGER_TRANSACTION", r.transaction().getId().toString(),
                    Map.of("reference", r.transaction().getReference(), "amountMinor", req.amountMinor(), "currency", req.currency()));
            return new TransferResponse(r.transaction().getId(), r.transaction().getReference(), "TRANSFER", r.transaction().getStatus().name(),
                    req.amountMinor(), req.currency(), from.getId(), to.getAccountNumber(), r.debitBalanceAfter(), r.transaction().getCreatedAt());
        } catch (BusinessException e) {
            metrics.counter("ledgercore.transfers.rejected", "code", e.code()).increment();
            throw e;
        }
    }
}
