package com.accusharp.hrms.security;

import com.accusharp.hrms.exception.TooManyRequestsException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IP-based brake on {@code /api/auth/login}, on top of - not instead of -
 * {@code AuthService}'s existing per-account lockout. That lockout alone is
 * itself a denial-of-service vector: employee userIds are low-entropy and
 * often guessable/enumerable (EMP001, HR001, ...), so an unauthenticated
 * caller who doesn't know anyone's password can still permanently lock any
 * account, including HR/ADMIN, after {@code security.max-failed-login-attempts}
 * bad guesses. This caps how many failed attempts one source address can make
 * across <em>all</em> accounts before it is throttled, independent of which
 * usernames it's trying.
 *
 * <p>In-memory and per-instance - correct for this application's current
 * single-instance deployment. A multi-instance deployment behind a load
 * balancer would need a shared store (e.g. Redis) for this to still be
 * effective per-IP rather than per-instance.
 */
@Component
public class LoginRateLimiter {

    private final int maxFailures;
    private final Duration windowDuration;
    private final ConcurrentHashMap<String, Window> byAddress = new ConcurrentHashMap<>();

    public LoginRateLimiter(
            @Value("${security.login-rate-limit.max-failures:15}") int maxFailures,
            @Value("${security.login-rate-limit.window-minutes:5}") long windowMinutes) {
        this.maxFailures = maxFailures;
        this.windowDuration = Duration.ofMinutes(windowMinutes);
    }

    private final class Window {
        private final Instant start = Instant.now();
        private final AtomicInteger count = new AtomicInteger();

        private boolean expired() {
            return Duration.between(start, Instant.now()).compareTo(windowDuration) > 0;
        }
    }

    /** Called before attempting authentication - rejects the request outright if this address is throttled. */
    public void assertNotThrottled(String address) {
        Window bucket = byAddress.get(address);
        if (bucket != null && !bucket.expired() && bucket.count.get() >= maxFailures) {
            throw new TooManyRequestsException(
                    "Too many failed login attempts from this address - try again in a few minutes");
        }
    }

    /** Called after a failed login (bad password, unknown username, locked/disabled account). */
    public void recordFailure(String address) {
        byAddress.compute(address, (key, existing) -> {
            Window bucket = (existing == null || existing.expired()) ? new Window() : existing;
            bucket.count.incrementAndGet();
            return bucket;
        });
    }

    /** Called after a successful login - a legitimate user shouldn't stay throttled by earlier typos. */
    public void recordSuccess(String address) {
        byAddress.remove(address);
    }
}
