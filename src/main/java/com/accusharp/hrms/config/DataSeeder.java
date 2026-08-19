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

    /** Demo-only credential for every seeded account. Never used past local/demo setup. */
    private static final String SEED_PASSWORD = "Accusharp@123";

    private final CategoryRepository categoryRepository;
    private final ShiftRepository shiftRepository;
    private final PlatformUserRepository platformUserRepository;
    private final SalaryRuleService salaryRuleService;
    private final PasswordEncoder passwordEncoder;

    @Bean
    ApplicationRunner seedReferenceData() {
        return args -> {
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
                .passwordHash(passwordEncoder.encode(SEED_PASSWORD))
                .email("owner@accusharp.example")
                .role(PlatformRole.PLATFORM_OWNER)
                .enabled(true)
                .createdAt(Instant.now())
                .build());
        log.info("seed.platform-owner username=platform_owner");
    }
}
