package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.PrincipalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // @Transactional here (not just on a caller) is what lets RefreshTokenService#consume
    // call this and have it actually commit even though that method deliberately
    // isn't itself @Transactional - see that method's Javadoc. A @Modifying query
    // needs a transaction to run in at all; without one, it throws
    // InvalidDataAccessApiUsageException rather than silently doing nothing.
    @Transactional
    @Modifying
    @Query("update RefreshToken r set r.revoked = true "
            + "where r.principalType = :type and r.principalId = :principalId and r.revoked = false")
    void revokeAllForPrincipal(@Param("type") PrincipalType type, @Param("principalId") String principalId);

    /**
     * Removes tokens that can no longer authenticate anyone - see
     * {@code RefreshTokenCleanupService} for why this exists and why revoked
     * rows get a grace period rather than being deleted the moment they are
     * spent.
     *
     * <p>Expiry alone is enough for the first arm: an expired token is refused
     * regardless of its revoked flag. The second arm covers tokens revoked long
     * enough ago that replay detection no longer needs them.
     */
    @Transactional
    @Modifying
    @Query("delete from RefreshToken r "
            + "where r.expiresAt < :now "
            + "or (r.revoked = true and r.createdAt < :revokedCutoff)")
    long deleteSpentTokens(@Param("now") Instant now, @Param("revokedCutoff") Instant revokedCutoff);
}
