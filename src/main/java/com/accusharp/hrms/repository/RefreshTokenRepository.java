package com.accusharp.hrms.repository;

import com.accusharp.hrms.entity.RefreshToken;
import com.accusharp.hrms.enums.PrincipalType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken r set r.revoked = true "
            + "where r.principalType = :type and r.principalId = :principalId and r.revoked = false")
    void revokeAllForPrincipal(@Param("type") PrincipalType type, @Param("principalId") String principalId);
}
