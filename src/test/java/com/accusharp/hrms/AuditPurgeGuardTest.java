package com.accusharp.hrms;

import com.accusharp.hrms.exception.BusinessRuleException;
import com.accusharp.hrms.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two guards on audit purging - the one irreversible operation in the
 * application, and the one that destroys the record of every other operation.
 *
 * <p>Runs with its own non-zero retention floor because the rest of the suite
 * disables it (those tests purge rows they wrote seconds earlier); without an
 * explicit value here the floor would go untested entirely.
 */
@SpringBootTest(properties = "app.audit.minimum-retention-days=365")
class AuditPurgeGuardTest {

    @Autowired private AuditService auditService;

    @Test
    @DisplayName("purging recent history is refused however it is confirmed")
    void recentHistoryCannotBePurged() {
        Instant yesterday = Instant.now().minus(Duration.ofDays(1));

        // The dangerous case: a mistyped date, or an actor erasing the trail of
        // what they just did. Confirmation does not make it acceptable.
        assertThatThrownBy(() -> auditService.purgeOlderThan(yesterday, yesterday))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Refusing to purge audit history newer than 365 days");
    }

    @Test
    @DisplayName("purging without confirming the range was exported is refused")
    void purgeRequiresExportConfirmation() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));

        assertThatThrownBy(() -> auditService.purgeOlderThan(longAgo, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Export the range first");

        // A mismatched confirmation is a different date than the one being
        // purged - almost certainly a copy-paste error, never an intent.
        assertThatThrownBy(() -> auditService.purgeOlderThan(longAgo, longAgo.plusSeconds(86_400)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Export the range first");
    }

    @Test
    @DisplayName("an old, correctly confirmed cutoff is allowed through")
    void oldConfirmedPurgeIsPermitted() {
        Instant longAgo = Instant.now().minus(Duration.ofDays(500));
        assertThatCode(() -> auditService.purgeOlderThan(longAgo, longAgo)).doesNotThrowAnyException();
    }
}
