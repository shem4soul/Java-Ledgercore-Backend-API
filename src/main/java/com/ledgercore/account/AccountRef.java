package com.ledgercore.account;

import java.util.UUID;

/**
 * Read-only projection used for authorisation/lookup BEFORE locking. It deliberately does not load the Account entity:
 * an entity read earlier in the transaction would stay in Hibernate's persistence context with its unlocked (stale) balance,
 * and the later SELECT ... FOR UPDATE would not refresh it. Entities are therefore only ever loaded via the locking query.
 */
public interface AccountRef {
    UUID getId();
    UUID getOwnerId();
    AccountType getType();
    String getAccountNumber();
    String getCurrency();
}
