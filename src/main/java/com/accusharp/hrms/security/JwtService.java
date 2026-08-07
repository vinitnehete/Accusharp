package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.PrincipalType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates short-lived JWT access tokens. Refresh tokens are
 * deliberately <em>not</em> JWTs - they are opaque, random, and looked up
 * against {@link com.accusharp.hrms.repository.RefreshTokenRepository} by
 * {@link RefreshTokenService}, so they can be revoked (logout, reuse
 * detection) without waiting for expiry. Access tokens carry no secret data,
 * only the identity and authorization claims needed to build a
 * {@link UserPrincipal} without a database round trip on every request.
 */
@Component
public class JwtService {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_COMPANY_ID = "companyId";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final String issuer;
    private final long accessTokenExpiryMinutes;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.issuer:accusharp-hrms}") String issuer,
                      @Value("${jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
    }

    public String generateAccessToken(UserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessTokenExpiryMinutes * 60);

        var builder = Jwts.builder()
                .subject(principal.getUsername())
                .issuer(issuer)
                .audience().add("accusharp-hrms-api").and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_TYPE, principal.getType().name())
                .claim(CLAIM_ROLE, principal.getRole());
        if (principal.getCompanyId() != null) {
            builder.claim(CLAIM_COMPANY_ID, principal.getCompanyId());
        }
        return builder.signWith(signingKey).compact();
    }

    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMinutes * 60;
    }

    /** @throws JwtException if the token is malformed, expired, or fails signature verification. */
    public UserPrincipal parseAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw e;
        }

        PrincipalType type = PrincipalType.valueOf(claims.get(CLAIM_TYPE, String.class));
        String role = claims.get(CLAIM_ROLE, String.class);
        Long companyId = claims.get(CLAIM_COMPANY_ID, Long.class);
        return UserPrincipal.fromClaims(type, claims.getSubject(), companyId, role);
    }
}
