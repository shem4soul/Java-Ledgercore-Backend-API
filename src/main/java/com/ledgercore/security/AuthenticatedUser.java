package com.ledgercore.security;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/** The principal placed in the SecurityContext after the JWT is verified. */
public record AuthenticatedUser(UUID id, String email, Set<Role> roles) {

    public Set<Permission> permissions() {
        Set<Permission> all = EnumSet.noneOf(Permission.class);
        roles.forEach(r -> all.addAll(r.permissions()));
        return all;
    }

    public boolean can(Permission permission) {
        return permissions().contains(permission);
    }
}
