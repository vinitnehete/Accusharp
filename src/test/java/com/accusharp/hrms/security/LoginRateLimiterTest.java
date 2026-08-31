package com.accusharp.hrms.security;

import com.accusharp.hrms.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level (no Spring context) test with its own small thresholds - see
 * {@code src/test/resources/application.properties} for why the HTTP tests
 * run against a much larger, effectively-disabled threshold instead of this
 * class's real production defaults.
 */
class LoginRateLimiterTest {

    @Test
    void allowsAttemptsUnderTheThreshold() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 5);

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThatCode(() -> limiter.assertNotThrottled("1.2.3.4")).doesNotThrowAnyException();
    }

    @Test
    void throttlesOnceTheThresholdIsReached() {
        LoginRateLimiter limiter = new LoginRateLimiter(3, 5);

        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThatThrownBy(() -> limiter.assertNotThrottled("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    void isScopedPerAddress() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 5);

        limiter.recordFailure("1.2.3.4");

        assertThatThrownBy(() -> limiter.assertNotThrottled("1.2.3.4"))
                .isInstanceOf(TooManyRequestsException.class);
        assertThatCode(() -> limiter.assertNotThrottled("5.6.7.8")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessClearsPriorFailuresForThatAddress() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 5);

        limiter.recordFailure("1.2.3.4");
        limiter.recordSuccess("1.2.3.4");
        limiter.recordFailure("1.2.3.4");

        assertThatCode(() -> limiter.assertNotThrottled("1.2.3.4")).doesNotThrowAnyException();
    }
}
