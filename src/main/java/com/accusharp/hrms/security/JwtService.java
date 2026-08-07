package com.accusharp.hrms.security;

import com.accusharp.hrms.enums.PrincipalType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
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
    private static final String AUDIENCE = "accusharp-hrms-api";

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
                .audience().add(AUDIENCE).and()
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

    /**
     * @throws JwtException if the token is malformed, expired, fails
     *     signature verification, or does not carry this API's issuer/audience
     *     (audience mismatch matters the moment this signing key is ever
     *     shared with another service issuing its own tokens - checking it
     *     costs nothing today and closes that door in advance).
     */
    public UserPrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(issuer)
                .requireAudience(AUDIENCE)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String typeClaim = claims.get(CLAIM_TYPE, String.class);
        if (typeClaim == null) {
            // Only reachable if this signing key ever issues a token missing a claim this
            // JwtService itself always sets - defensive, not currently exercisable internally.
            throw new MalformedJwtException("Token is missing the required '" + CLAIM_TYPE + "' claim");
        }
        PrincipalType type;
        try {
            type = PrincipalType.valueOf(typeClaim);
        } catch (IllegalArgumentException notAKnownType) {
            throw new MalformedJwtException("Token carries an unrecognized '" + CLAIM_TYPE + "' claim: " + typeClaim);
        }

        String role = claims.get(CLAIM_ROLE, String.class);
        Long companyId = claims.get(CLAIM_COMPANY_ID, Long.class);
        return UserPrincipal.fromClaims(type, claims.getSubject(), companyId, role);
    }
}
