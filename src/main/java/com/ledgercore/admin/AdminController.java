package com.ledgercore.admin;

import com.ledgercore.account.AccountDtos.AccountResponse;
import com.ledgercore.account.AccountDtos.LimitRequest;
import com.ledgercore.account.AccountService;
import com.ledgercore.account.AccountStatus;
import com.ledgercore.audit.AuditService;
import com.ledgercore.audit.AuditService.AuditView;
import com.ledgercore.common.PageResult;
import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.idempotency.IdempotencyService.Outcome;
import com.ledgercore.ledger.AdminLedgerService;
import com.ledgercore.ledger.ReconciliationService;
import com.ledgercore.ledger.TransactionQueryService;
import com.ledgercore.ledger.TransactionQueryService.TransactionView;
import com.ledgercore.ledger.TransferDtos.AdjustmentRequest;
import com.ledgercore.ledger.TransferDtos.ReverseRequest;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import com.ledgercore.outbox.OutboxAdminService;
import com.ledgercore.outbox.OutboxAdminService.OutboxView;
import com.ledgercore.security.AuthenticatedUser;
import com.ledgercore.user.UserDtos.EnabledRequest;
import com.ledgercore.user.UserDtos.RolesRequest;
import com.ledgercore.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Back-office API. Every endpoint is guarded by a specific permission; every mutation is written to the audit log. */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final AdminService admin;
    private final AccountService accounts;
    private final AdminLedgerService adminLedger;
    private final IdempotencyService idempotency;
    private final TransactionQueryService queries;
    private final ReconciliationService reconciliation;
    private final OutboxAdminService outbox;
    private final AuditService audit;

    public AdminController(AdminService admin, AccountService accounts, AdminLedgerService adminLedger, IdempotencyService idempotency,
                           TransactionQueryService queries, ReconciliationService reconciliation, OutboxAdminService outbox, AuditService audit) {
        this.admin = admin;
        this.accounts = accounts;
        this.adminLedger = adminLedger;
        this.idempotency = idempotency;
        this.queries = queries;
        this.reconciliation = reconciliation;
        this.outbox = outbox;
        this.audit = audit;
    }

    // ---- users ----
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public PageResult<UserResponse> users(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return admin.listUsers(page, Math.min(Math.max(size, 1), 100));
    }

    @PatchMapping("/users/{id}/enabled")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse setEnabled(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @RequestBody EnabledRequest req) {
        return admin.setEnabled(actor.id(), id, req.enabled());
    }

    @PatchMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserResponse setRoles(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody RolesRequest req) {
        return admin.setRoles(actor.id(), id, req.roles());
    }

    // ---- accounts ----
    @GetMapping("/accounts")
    @PreAuthorize("hasAuthority('ACCOUNT_READ_ANY')")
    public PageResult<AccountResponse> accounts(@RequestParam(required = false) AccountStatus status,
                                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var r = accounts.adminList(status, page, Math.min(Math.max(size, 1), 100));
        return new PageResult<>(r.items().stream().map(AccountResponse::from).toList(), r.page(), r.size(), r.total());
    }

    @PostMapping("/accounts/{id}/freeze")
    @PreAuthorize("hasAuthority('ACCOUNT_FREEZE')")
    public AccountResponse freeze(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id,
                                  @RequestParam(defaultValue = "") String reason) {
        return AccountResponse.from(accounts.setStatus(actor.id(), id, AccountStatus.FROZEN, reason));
    }

    @PostMapping("/accounts/{id}/unfreeze")
    @PreAuthorize("hasAuthority('ACCOUNT_FREEZE')")
    public AccountResponse unfreeze(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id,
                                    @RequestParam(defaultValue = "") String reason) {
        return AccountResponse.from(accounts.setStatus(actor.id(), id, AccountStatus.ACTIVE, reason));
    }

    @PatchMapping("/accounts/{id}/limit")
    @PreAuthorize("hasAuthority('ACCOUNT_LIMIT_UPDATE')")
    public AccountResponse limit(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id, @Valid @RequestBody LimitRequest req) {
        return AccountResponse.from(accounts.setDailyLimit(actor.id(), id, req.dailyLimitMinor()));
    }

    // ---- ledger operations (idempotent) ----
    @PostMapping("/accounts/{id}/deposit")
    @PreAuthorize("hasAuthority('LEDGER_ADJUST')")
    public ResponseEntity<TransferResponse> deposit(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AdjustmentRequest req) {
        String hash = IdempotencyService.hash("DEPOSIT", id.toString(), String.valueOf(req.amountMinor()), req.description());
        Outcome<TransferResponse> out = idempotency.execute(actor.id(), key, "DEPOSIT", hash, TransferResponse.class,
                () -> adminLedger.deposit(actor.id(), id, req.amountMinor(), req.description()));
        return wrap(out);
    }

    @PostMapping("/accounts/{id}/withdraw")
    @PreAuthorize("hasAuthority('LEDGER_ADJUST')")
    public ResponseEntity<TransferResponse> withdraw(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id,
                                                     @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody AdjustmentRequest req) {
        String hash = IdempotencyService.hash("WITHDRAW", id.toString(), String.valueOf(req.amountMinor()), req.description());
        Outcome<TransferResponse> out = idempotency.execute(actor.id(), key, "WITHDRAWAL", hash, TransferResponse.class,
                () -> adminLedger.withdraw(actor.id(), id, req.amountMinor(), req.description()));
        return wrap(out);
    }

    @PostMapping("/transactions/{id}/reverse")
    @PreAuthorize("hasAuthority('TRANSACTION_REVERSE')")
    public ResponseEntity<TransferResponse> reverse(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id,
                                                    @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ReverseRequest req) {
        String hash = IdempotencyService.hash("REVERSE", id.toString(), req.reason());
        Outcome<TransferResponse> out = idempotency.execute(actor.id(), key, "REVERSAL", hash, TransferResponse.class,
                () -> adminLedger.reverse(actor.id(), id, req.reason()));
        return wrap(out);
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('TRANSACTION_READ_ANY')")
    public PageResult<TransactionView> transactions(@RequestParam(required = false) String type,
                                                    @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.adminSearch(type, page, size);
    }

    // ---- audit, outbox, reconciliation, stats ----
    @GetMapping("/audit-logs")
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    public List<AuditView> auditLogs(@RequestParam(required = false) String action, @RequestParam(required = false) UUID actorId,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return audit.search(action, actorId, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @GetMapping("/outbox")
    @PreAuthorize("hasAuthority('OUTBOX_MANAGE')")
    public List<OutboxView> outbox(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return outbox.list(status, Math.max(page, 0), Math.min(Math.max(size, 1), 200));
    }

    @PostMapping("/outbox/{id}/retry")
    @PreAuthorize("hasAuthority('OUTBOX_MANAGE')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        outbox.retry(actor.id(), id);
    }

    @PostMapping("/reconciliation/run")
    @PreAuthorize("hasAuthority('RECONCILE_RUN')")
    public ReconciliationService.Report reconcile() {
        return reconciliation.run();
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('ACCOUNT_READ_ANY')")
    public Map<String, Object> stats() {
        return admin.stats();
    }

    private static ResponseEntity<TransferResponse> wrap(Outcome<TransferResponse> out) {
        return out.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(out.body())
                : ResponseEntity.status(HttpStatus.CREATED).body(out.body());
    }
}
