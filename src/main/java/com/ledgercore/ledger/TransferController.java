package com.ledgercore.ledger;

import com.ledgercore.idempotency.IdempotencyService;
import com.ledgercore.idempotency.IdempotencyService.Outcome;
import com.ledgercore.ledger.TransactionQueryService.TransactionView;
import com.ledgercore.ledger.TransferDtos.TransferRequest;
import com.ledgercore.ledger.TransferDtos.TransferResponse;
import com.ledgercore.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Transfers")
@RestController
@RequestMapping("/api/v1")
public class TransferController {
    private final IdempotencyService idempotency;
    private final TransferService transfers;
    private final TransactionQueryService queries;

    public TransferController(IdempotencyService idempotency, TransferService transfers, TransactionQueryService queries) {
        this.idempotency = idempotency;
        this.transfers = transfers;
        this.queries = queries;
    }

    /**
     * Send money. The Idempotency-Key header is mandatory: retrying with the same key (after a timeout, say) can never debit twice.
     * 201 = executed now; 200 + "Idempotent-Replayed: true" = the original result is returned again.
     */
    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public ResponseEntity<TransferResponse> transfer(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "Unique per logical transfer, 8-80 chars") @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransferRequest req) {
        String hash = IdempotencyService.hash(req.fromAccountId().toString(), req.toAccountNumber(),
                String.valueOf(req.amountMinor()), req.currency(), req.description() == null ? "" : req.description());
        Outcome<TransferResponse> out = idempotency.execute(user.id(), key, "TRANSFER", hash, TransferResponse.class,
                () -> transfers.transfer(user, req));
        return out.replayed()
                ? ResponseEntity.ok().header("Idempotent-Replayed", "true").body(out.body())
                : ResponseEntity.status(HttpStatus.CREATED).body(out.body());
    }

    @GetMapping("/transactions/{id}")
    public TransactionView transaction(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return queries.get(user, id);
    }
}
