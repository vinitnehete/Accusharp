package com.accusharp.hrms.config;

import com.accusharp.hrms.entity.Category;
import com.accusharp.hrms.entity.PlatformUser;
import com.accusharp.hrms.entity.Shift;
import com.accusharp.hrms.enums.PlatformRole;
import com.accusharp.hrms.repository.CategoryRepository;
import com.accusharp.hrms.repository.PlatformUserRepository;
import com.accusharp.hrms.repository.ShiftRepository;
import com.accusharp.hrms.service.SalaryRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Seeds the masters an empty database cannot work without - the four
 * standard shifts, the shared employee-grade categories, the default salary
 * rule, and the platform-owner account.
 *
 * <p>Idempotent: nothing is written if the data already exists. Disable with
 * {@code hrms.seed.enabled=false}.
 */
@Configuration
@ConditionalOnProperty(name = "hrms.seed.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DataSeeder {

    // Same reasoning and same pattern as JwtService's INSECURE_DEFAULT_SECRET
    // guard: this value is committed and public, so on an empty database this
    // seeder would otherwise silently create a PLATFORM_OWNER account (full
    // company create/delete + audit-purge rights) with a password anyone who
    // has read this source file already knows. Refusing to start is the same
    // choice made there, for the same reason.
    private static final String INSECURE_DEFAULT_SEED_PASSWORD = "Accusharp@123";

    @Value("${hrms.seed.platform-owner-password}")
    private String seedPassword;

    private final CategoryRepository categoryRepository;
    private final ShiftRepository shiftRepository;
    private final PlatformUserRepository platformUserRepository;
    private final SalaryRuleService salaryRuleService;
    private final PasswordEncoder passwordEncoder;

    @Bean
    ApplicationRunner seedReferenceData() {
        return args -> {
            if (INSECURE_DEFAULT_SEED_PASSWORD.equals(seedPassword)
                    && !platformUserRepository.existsByUsername("platform_owner")) {
                throw new IllegalStateException(
                        "hrms.seed.enabled is true and hrms.seed.platform-owner-password is still the "
                                + "placeholder value committed in source. On an empty database this would "
                                + "create a PLATFORM_OWNER account (full company create/delete and audit-purge "
                                + "rights) with a password anyone who has read this repository already knows. "
                                + "Set HRMS_SEED_PLATFORM_OWNER_PASSWORD to a real, random value, or set "
                                + "hrms.seed.enabled=false if this database already has its own platform owner "
                                + "or doesn't need one seeded.");
            }
            salaryRuleService.getActiveRule();
            seedShifts();
            seedCategories();
            seedPlatformOwner();
        };
    }

    /** Common employee grades, shared across every company; a company ADMIN/HR may add more via /api/categories. */
    private void seedCategories() {
        createCategory("WORKER", "Worker");
        createCategory("STAFF", "Staff");
        createCategory("SUPERVISOR", "Supervisor");
        createCategory("MANAGER", "Manager");
        createCategory("DIRECTOR", "Director");
    }

    private void createCategory(String code, String name) {
        if (categoryRepository.existsByCategoryCodeAndCompanyIsNull(code)) {
            return;
        }
        categoryRepository.save(Category.builder().categoryCode(code).categoryName(name).build());
        log.info("seed.category code={}", code);
    }

    /** The four shifts from the specification; admins may add custom ones. */
    private void seedShifts() {
        createShift("MORNING", "Morning", LocalTime.of(6, 0), LocalTime.of(19, 0));
        createShift("GENERAL", "General", LocalTime.of(9, 0), LocalTime.of(19, 0));
        createShift("EVENING", "Evening", LocalTime.of(14, 0), LocalTime.of(23, 0));
        // Ends before it starts, so the engine treats it as crossing midnight.
        createShift("NIGHT", "Night", LocalTime.of(18, 0), LocalTime.of(8, 0));
    }

    private void createShift(String code, String name, LocalTime start, LocalTime end) {
        if (shiftRepository.existsByShiftCode(code)) {
            return;
        }
        shiftRepository.save(Shift.builder()
                .shiftCode(code)
                .shiftName(name)
                .startTime(start)
                .endTime(end)
                .workingHours(8)
                .breakMinutes(0)
                .graceMinutes(120)
                .overtimeWindowMinutes(240)
                .build());
        log.info("seed.shift code={}", code);
    }

    /** A platform-level account for company onboarding, separate from any Employee. */
    private void seedPlatformOwner() {
        if (platformUserRepository.existsByUsername("platform_owner")) {
            return;
        }
        platformUserRepository.save(PlatformUser.builder()
                .username("platform_owner")
                .passwordHash(passwordEncoder.encode(seedPassword))
                .email("owner@accusharp.example")
                .role(PlatformRole.PLATFORM_OWNER)
                .enabled(true)
                .createdAt(Instant.now())
                .build());
        log.info("seed.platform-owner username=platform_owner");
    }
}
