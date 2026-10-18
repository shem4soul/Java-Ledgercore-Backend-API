package com.ledgercore.security;

import com.ledgercore.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/** Issues and verifies short-lived access tokens (HS256). For multi-service setups prefer asymmetric keys (RS256/KMS). */
@Service
public class JwtService {
    private final SecretKey key;
    private final AppProperties.Jwt props;
    private final Clock clock;

    public JwtService(AppProperties props, Clock clock) {
        this.props = props.jwt();
        this.key = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    public String issueAccessToken(UUID userId, String email, Set<Role> roles) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(props.issuer())
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .claim("email", email)
                .claim("roles", roles.stream().map(Enum::name).toList())
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return props.accessTtl().toSeconds();
    }

    /** Returns the principal for a valid token, or empty if the token is invalid, expired or tampered with. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(props.issuer())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Set<Role> roles = EnumSet.noneOf(Role.class);
            Object raw = c.get("roles");
            if (raw instanceof List<?> list) {
                for (Object o : list) roles.add(Role.valueOf(String.valueOf(o)));
            }
            return Optional.of(new AuthenticatedUser(UUID.fromString(c.getSubject()), c.get("email", String.class), roles));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
