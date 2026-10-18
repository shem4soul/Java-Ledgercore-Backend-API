package com.ledgercore.security;

import static com.ledgercore.security.Permission.*;

import java.util.EnumSet;
import java.util.Set;

/** Roles map to fine-grained permissions; endpoints check permissions, not role names. */
public enum Role {
    CUSTOMER(EnumSet.of(TRANSFER_CREATE, ACCOUNT_READ_OWN)),
    SUPPORT(EnumSet.of(ACCOUNT_READ_ANY, TRANSACTION_READ_ANY)),
    AUDITOR(EnumSet.of(AUDIT_READ, ACCOUNT_READ_ANY, TRANSACTION_READ_ANY, RECONCILE_RUN)),
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) { this.permissions = permissions; }

    public Set<Permission> permissions() { return permissions; }
}
