package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.PrincipalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
}
