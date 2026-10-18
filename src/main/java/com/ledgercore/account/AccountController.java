package com.ledgercore.account;

import com.ledgercore.account.AccountDtos.AccountResponse;
import com.ledgercore.account.AccountDtos.BeneficiaryResponse;
import com.ledgercore.account.AccountDtos.OpenAccountRequest;
import com.ledgercore.common.PageResult;
import com.ledgercore.ledger.TransactionQueryService;
import com.ledgercore.ledger.TransactionQueryService.HistoryItem;
import com.ledgercore.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Accounts")
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;
    private final TransactionQueryService queries;

    public AccountController(AccountService accounts, TransactionQueryService queries) {
        this.accounts = accounts;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ACCOUNT_READ_OWN')")
    public AccountResponse open(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.from(accounts.open(user.id(), req.currency()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNT_READ_OWN')")
    public List<AccountResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return accounts.listMine(user.id()).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return AccountResponse.from(accounts.getVisible(user, id));
    }

    @GetMapping("/{id}/transactions")
    public PageResult<HistoryItem> statement(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        accounts.getVisible(user, id);   // authorisation: owner or staff, otherwise 404
        return queries.history(id, page, size);
    }

    /** Confirm a beneficiary before paying: returns a masked account name. */
    @GetMapping("/lookup/{accountNumber}")
    @PreAuthorize("hasAuthority('TRANSFER_CREATE')")
    public BeneficiaryResponse lookup(@PathVariable String accountNumber) {
        return accounts.lookup(accountNumber);
    }
}
