package com.accusharp.hrms.security;

import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.AuditOutcome;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.exception.AuthenticationFailedException;
import com.accusharp.hrms.repository.RefreshTokenRepository;
import com.accusharp.hrms.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and validates opaque refresh tokens. Only the SHA-256 hash is ever
 * persisted, so a database read alone cannot be replayed as a credential.
 * Rotation is mandatory: every successful refresh revokes the token it
 * consumed and issues a new one, so a leaked-but-unused refresh token that
 * later gets replayed is detectable (the hash it presents is already
 * revoked).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    @Value("${jwt.refresh-token-expiry-days:7}")
    private long refreshTokenExpiryDays;

    @Transactional
    public String issue(PrincipalType type, String principalId) {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken entity = RefreshToken.builder()
                .tokenHash(hash(rawToken))
                .principalType(type)
                .principalId(principalId)
                .expiresAt(Instant.now().plusSeconds(refreshTokenExpiryDays * 86400))
                .revoked(false)
                .createdAt(Instant.now())
                .build();
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    /**
     * Validates the token, revokes it, and returns who it belonged to -
     * callers must issue a new one.
     *
     * <p>A token presented here already {@link RefreshToken#isRevoked()} is
     * not just an expired/already-used credential - because rotation is
     * mandatory, the <em>only</em> way a revoked token gets replayed is if
     * someone other than whoever rotated it last is now holding a stale copy
     * (a stolen token used after the legitimate client already refreshed).
     * Rather than just rejecting this one request, every other live token
     * for the same principal is revoked too, forcing a fresh login
     * everywhere - the standard "reuse detection revokes the whole family"
     * response, closing the gap the audit's authentication review flagged
     * (previously only the replayed token itself was revoked, leaving
     * whichever session raced ahead and rotated first still valid).
     *
     * <p>Deliberately <b>not</b> {@code @Transactional} at this method level -
     * same reasoning as {@code AuthService#login}'s Javadoc: the reuse branch
     * below has to actually commit the family-wide revocation even though it
     * then throws. Wrapping this whole method in one transaction would roll
     * that write back along with the exception, silently defeating the
     * detection it exists for. Each repository call below already commits on
     * its own via Spring Data's per-method transaction instead.
     */
    public RefreshToken consume(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new AuthenticationFailedException("Invalid refresh token"));
        if (token.isRevoked()) {
            log.warn("refresh-token.reuse-detected principalType={} principalId={} - revoking entire session family",
                    token.getPrincipalType(), token.getPrincipalId());
            refreshTokenRepository.revokeAllForPrincipal(token.getPrincipalType(), token.getPrincipalId());
            auditService.recordWithActor(token.getPrincipalId(), token.getPrincipalType(), null,
                    "REFRESH_TOKEN_REUSE_DETECTED", "Account", token.getPrincipalId(), AuditOutcome.FAILURE,
                    "A previously-rotated refresh token was replayed - every session for this account was signed out");
            throw new AuthenticationFailedException("Refresh token has already been used or revoked");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthenticationFailedException("Refresh token has expired");
        }
        token.setRevoked(true);
        refreshTokenRepository.save(token);
        return token;
    }

    /** Returns the token that was revoked, if it existed - callers (audit logging) attribute the action to it. */
    @Transactional
    public Optional<RefreshToken> revoke(String rawToken) {
        return refreshTokenRepository.findByTokenHash(hash(rawToken))
                .map(token -> {
                    token.setRevoked(true);
                    return refreshTokenRepository.save(token);
                });
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
