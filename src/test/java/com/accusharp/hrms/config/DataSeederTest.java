package com.accusharp.hrms.config;

import com.accusharp.hrms.repository.CategoryRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level (no Spring context) test for the fail-fast guard added around
 * the seeded platform-owner password - see {@link DataSeeder}'s Javadoc and
 * the same reasoning as {@code JwtServiceTest} would apply to
 * {@code INSECURE_DEFAULT_SECRET}. Constructs {@link DataSeeder} directly and
 * calls its package-private {@code seedReferenceData()} bean method, so this
 * doesn't need a full application context to prove the guard actually blocks
 * startup rather than just logging a warning.
 */
class DataSeederTest {

    private static final String PLACEHOLDER = "Accusharp@123";

    private final CategoryRepository categoryRepository = mock(CategoryRepository.class);
    private final ShiftRepository shiftRepository = mock(ShiftRepository.class);
    private final PlatformUserRepository platformUserRepository = mock(PlatformUserRepository.class);
    private final SalaryRuleService salaryRuleService = mock(SalaryRuleService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

    @Test
    @DisplayName("refuses to start when the seed password is still the placeholder and no platform owner exists yet")
    void refusesToStartWithPlaceholderPasswordOnAnEmptyDatabase() {
        when(platformUserRepository.existsByUsername("platform_owner")).thenReturn(false);
        DataSeeder seeder = seederWithPassword(PLACEHOLDER);

        ApplicationRunner runner = seeder.seedReferenceData();

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HRMS_SEED_PLATFORM_OWNER_PASSWORD");
        verify(salaryRuleService, never()).getActiveRule();
    }

    @Test
    @DisplayName("does not re-throw on an already-seeded database, even with the placeholder still configured")
    void doesNotBlockAnAlreadySeededDatabase() throws Exception {
        when(platformUserRepository.existsByUsername("platform_owner")).thenReturn(true);
        when(salaryRuleService.getActiveRule()).thenReturn(null);
        DataSeeder seeder = seederWithPassword(PLACEHOLDER);

        ApplicationRunner runner = seeder.seedReferenceData();
        runner.run(null);

        verify(salaryRuleService).getActiveRule();
    }

    @Test
    @DisplayName("starts normally once a real password is configured")
    void startsNormallyWithARealPassword() throws Exception {
        when(platformUserRepository.existsByUsername("platform_owner")).thenReturn(false);
        when(salaryRuleService.getActiveRule()).thenReturn(null);
        DataSeeder seeder = seederWithPassword("a-real-randomly-generated-password");

        ApplicationRunner runner = seeder.seedReferenceData();
        runner.run(null);

        assertThat(true).isTrue(); // reaching here without throwing is the assertion
    }

    private DataSeeder seederWithPassword(String password) {
        DataSeeder seeder = new DataSeeder(
                categoryRepository, shiftRepository, platformUserRepository, salaryRuleService, passwordEncoder);
        ReflectionTestUtils.setField(seeder, "seedPassword", password);
        return seeder;
    }
}
