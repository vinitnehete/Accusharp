package com.accusharp.hrms.util;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * A one-time password for a newly created login - never logged, returned
 * exactly once in the response that creates it. Used by both {@code
 * CompanyOnboardingService} (the first admin of a new company) and {@code
 * EmployeeService} (every employee created afterward, who would otherwise
 * have no password at all and could never log in).
 */
public final class TemporaryPasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
    }

    /** Guarantees at least one digit and one uppercase letter so it always passes typical password policy. */
    public static String generate() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "Tp7-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
