package com.accusharp.hrms.service;

import com.accusharp.hrms.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Deletes refresh tokens that can no longer authenticate anyone.
 *
 * <p>Nothing removed rows from {@code refresh_token} before this existed. Every
 * login inserted one and every refresh inserted another, so the table grew for
 * the life of the deployment and never shrank. Two consequences, one
 * operational and one security:
 *
 * <ul>
 *   <li>The table is on the hot path of every token refresh, looked up by hash.
 *       Unbounded growth makes that index steadily larger for no benefit.</li>
 *   <li>More importantly, revoked and expired token hashes lingered
 *       indefinitely. They cannot be used to log in - {@code consume()} checks
 *       both flags - but keeping a permanent record of every session an
 *       employee has ever held is data retained for no purpose, and data
 *       retained for no purpose is only ever a liability in a breach.</li>
 * </ul>
 *
 * <p>Runs daily at 03:00, deliberately after {@code DefaultRosterService}'s
 * 02:00 monthly job so the two never contend. Deletion is limited to rows that
 * are already useless: expired, or revoked before the retention cutoff.
 * Recently-revoked rows are kept for a grace period because
 * {@code RefreshTokenService} relies on finding a revoked row to detect replay
 * of a stolen token - purging those immediately would turn a detected theft
 * into a silent "invalid token".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * How long a revoked token's hash is kept so replay detection still fires.
     * Defaults to the refresh token's own lifetime: past that point the token
     * would have expired anyway, so nothing is lost.
     */
    @Value("${app.security.refresh-token-retention-days:7}")
    private long retentionDays;

    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional
    public void purgeSpentTokens() {
        Instant now = Instant.now();
        Instant revokedCutoff = now.minusSeconds(retentionDays * 86400);

        long deleted = refreshTokenRepository.deleteSpentTokens(now, revokedCutoff);
        if (deleted > 0) {
            log.info("refresh-token.cleanup deleted={} expiredBefore={} revokedBefore={}",
                    deleted, now, revokedCutoff);
        }
    }
}
