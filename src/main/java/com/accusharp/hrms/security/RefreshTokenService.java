package com.accusharp.hrms.security;

import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.PrincipalType;
import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
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
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

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

    /** Validates the token, revokes it, and returns who it belonged to - callers must issue a new one. */
    @Transactional
    public RefreshToken consume(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BusinessRuleException("Invalid refresh token"));
        if (token.isRevoked()) {
            throw new BusinessRuleException("Refresh token has already been used or revoked");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessRuleException("Refresh token has expired");
        }
        token.setRevoked(true);
        refreshTokenRepository.save(token);
        return token;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
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
