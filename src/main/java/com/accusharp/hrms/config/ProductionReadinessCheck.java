package com.accusharp.hrms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.List;

/**
 * Startup checks for configuration that is safe locally and dangerous in
 * production.
 *
 * <p>The application already refuses to start on a placeholder JWT secret,
 * seed password or encryption key. Those are unambiguous - a placeholder is
 * never right. The settings here are different: each is correct for local
 * development and wrong once the app is reachable by anyone else, so they
 * cannot simply be rejected. The rule used is that anything actively
 * exploitable is fatal, and anything merely suspicious is a loud warning
 * naming the exact property to change.
 *
 * <p>Fatal, because there is no configuration in which they are acceptable:
 * <ul>
 *   <li>A wildcard CORS origin. Combined with {@code allowCredentials(true)} -
 *       which this app needs for the refresh cookie - it would let any site on
 *       the internet make authenticated requests as a signed-in user. Spring
 *       rejects the combination itself at request time; failing at startup
 *       turns a runtime surprise into a deployment that never happens.</li>
 *   <li>An empty CORS origin list, which silently blocks every browser client
 *       and looks like a network fault rather than a config error.</li>
 * </ul>
 *
 * <p>Warned about, because they are normal in development:
 * localhost CORS origins, an insecure refresh cookie, seeding left enabled,
 * and no trusted proxy configured. Each of those is exactly right on a laptop.
 */
@Configuration
@Slf4j
public class ProductionReadinessCheck {

    private final Environment environment;

    // Same default as SecurityConfig, deliberately - a check that validated a
    // different value than the one actually applied would be worse than none.
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8081,http://localhost:19006}")
    private List<String> allowedOrigins;

    @Value("${app.auth.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${hrms.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${app.security.trusted-proxies:}")
    private List<String> trustedProxies;

    public ProductionReadinessCheck(Environment environment) {
        this.environment = environment;
    }

    @Bean
    ApplicationRunner verifyDeploymentConfiguration() {
        return args -> {
            boolean localDev = environment.acceptsProfiles(Profiles.of("h2"));

            if (allowedOrigins.stream().anyMatch(origin -> origin.trim().equals("*"))) {
                throw new IllegalStateException(
                        "app.cors.allowed-origins contains '*'. This API sends credentials "
                                + "(the refresh cookie), so a wildcard origin would let any website "
                                + "make authenticated requests as a signed-in user. Set "
                                + "CORS_ALLOWED_ORIGINS to the exact origins your frontend is served "
                                + "from.");
            }
            if (allowedOrigins.stream().noneMatch(origin -> !origin.trim().isEmpty())) {
                throw new IllegalStateException(
                        "app.cors.allowed-origins is empty - no browser client would be able to call "
                                + "this API. Set CORS_ALLOWED_ORIGINS to the origins your frontend is "
                                + "served from.");
            }

            if (localDev) {
                log.info("startup.config profile=h2 - local development settings are expected here");
                return;
            }

            warnIf(allowedOrigins.stream().anyMatch(o -> o.contains("localhost") || o.contains("127.0.0.1")),
                    "app.cors.allowed-origins still contains a localhost origin ({}). Outside local "
                            + "development this should be your real frontend origin only - set "
                            + "CORS_ALLOWED_ORIGINS.", allowedOrigins);

            warnIf(!cookieSecure,
                    "app.auth.cookie.secure is false outside the h2 profile. The refresh token will be "
                            + "sent over plaintext HTTP. Set APP_AUTH_COOKIE_SECURE=true and terminate "
                            + "TLS in front of the application.");

            warnIf(seedEnabled,
                    "hrms.seed.enabled is true. Reference data and a platform_owner account will be "
                            + "created on an empty database. Once this environment is provisioned, set "
                            + "hrms.seed.enabled=false so a future empty-schema accident cannot mint a "
                            + "new platform owner.");

            warnIf(trustedProxies.stream().allMatch(p -> p.trim().isEmpty()),
                    "app.security.trusted-proxies is empty, so X-Forwarded-For is ignored and login "
                            + "throttling keys on the direct socket address. Correct if nothing is in "
                            + "front of the app; if a proxy or load balancer is, set APP_TRUSTED_PROXIES "
                            + "to its address or every caller will share one rate-limit bucket.");
        };
    }

    private void warnIf(boolean condition, String message, Object... args) {
        if (condition) {
            log.warn("startup.config " + message, args);
        }
    }
}
