package com.accusharp.hrms.config;

import com.accusharp.hrms.security.JwtAuthenticationFilter;
import com.accusharp.hrms.security.JwtService;
import com.accusharp.hrms.security.RestAccessDeniedHandler;
import com.accusharp.hrms.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security. Two things worth being explicit about:
 *
 * <p><b>CSRF is disabled deliberately, and that is still correct now that a
 * cookie exists.</b> The earlier version of this note said CSRF "matters
 * again only if a future change starts authenticating via cookies" - that
 * change has now happened, so here is the re-derivation rather than an
 * assumption carried forward.
 *
 * <p>There are two credentials, and they are split on purpose:
 * <ul>
 *   <li>The <b>access token</b> authenticates all ~119 business endpoints and
 *       travels as an {@code Authorization} header the SPA sets from memory.
 *       A cross-site page cannot set that header, so a forged request to any
 *       business endpoint arrives unauthenticated and is rejected. This is
 *       why the access token was not moved into a cookie: doing so would have
 *       made every state-changing endpoint in the application CSRF-reachable
 *       and required a token dance on all of them.</li>
 *   <li>The <b>refresh token</b> is an httpOnly cookie, so it <em>is</em>
 *       attached automatically - but only to {@code /api/auth/refresh} and
 *       {@code /api/auth/logout} ({@code Path=/api/auth}), and only on
 *       same-site requests ({@code SameSite=Strict}). A cross-site page
 *       cannot make the browser send it, which is the CSRF control for those
 *       two endpoints. Even if it could, the attacker cannot read the
 *       response - the CORS allow-list below permits only this application's
 *       own origins - so a forced refresh yields no token, only a rotation.</li>
 * </ul>
 *
 * <p>What would invalidate this reasoning: moving the access token into a
 * cookie, widening the refresh cookie's {@code Path}, or relaxing
 * {@code SameSite}. Any of those requires turning CSRF protection back on.
 *
 * <p><b>Response headers.</b> The header block below is not boilerplate. This
 * API serves one HTML document ({@code GET /api/salary-slips/&#123;id&#125;/print}),
 * and it carries salary data, so it must not be framable, sniffable, or able
 * to load anything off-origin. The same headers also harden every JSON
 * response at no cost.
 *
 * <p><b>Phase 2.</b> Every {@code /api/**} endpoint other than
 * {@code /api/auth/**} now requires authentication by default
 * ({@code anyRequest().authenticated()}), and each controller method carries
 * its own {@code @PreAuthorize("@authz.can('...')")} for the specific
 * permission it needs - see {@link com.accusharp.hrms.security.AuthorizationService}
 * and {@link com.accusharp.hrms.config.PermissionSeeder}.
 *
 * <p><b>Two different 403 paths exist, and only one is live today.</b> A
 * missing/invalid token is rejected right here, at the filter-chain level,
 * before any controller runs - that is a genuine {@link RestAuthenticationEntryPoint}
 * 401. A {@code @PreAuthorize} denial, however, is thrown by Spring's method
 * security interceptor <em>during</em> the controller method invocation,
 * inside {@code DispatcherServlet}'s own try/catch - so Spring MVC's
 * {@code @ExceptionHandler} resolution (i.e. {@code GlobalExceptionHandler})
 * always gets first refusal and handles it before the exception could ever
 * propagate back out to this filter chain's {@link RestAccessDeniedHandler}.
 * Verified empirically, not just reasoned about - see the
 * {@code AttendanceApiHttpTest.roleIsEnforcedOverHttp} /
 * {@code AuthApiHttpTest.businessEndpointWithNoTokenIsRejected} pair.
 * {@link RestAccessDeniedHandler} is kept anyway as the correct handler for
 * an {@code AccessDeniedException} thrown at the filter-chain level itself -
 * e.g. a future {@code authorizeHttpRequests().hasRole(...)} rule - which
 * this app does not currently have any of. Both handlers produce the
 * identical {@code ApiError} shape on purpose, so which one fires is an
 * implementation detail, not a client-visible difference.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final Environment environment;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8081,http://localhost:19006}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Gated on the "h2" profile itself, not just on spring.h2.console.enabled
        // in application-h2.properties - the audit's security review flagged that
        // this permitAll rule had no guard of its own, so activating the h2
        // profile anywhere reachable (a misconfigured deploy, a copy-pasted run
        // command) would expose an unauthenticated, framable SQL console even if
        // that second property were somehow left at its default. Two independent
        // conditions now both have to be true, not one.
        boolean h2ConsoleActive = environment.acceptsProfiles(Profiles.of("h2"));

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/auth/**").permitAll();
                    if (h2ConsoleActive) {
                        auth.requestMatchers("/h2-console/**").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .headers(headers -> {
                    // Sent only over HTTPS (Spring checks the request itself), so this is
                    // inert in local HTTP development and active the moment TLS is in front.
                    headers.httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31_536_000));
                    headers.referrerPolicy(referrer -> referrer
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    if (h2ConsoleActive) {
                        // The H2 console renders itself in a frame and needs its own
                        // scripts - only relaxed when the h2 profile is actually active,
                        // the same condition as the matcher above. No CSP is applied in
                        // this mode because the console cannot run under one.
                        headers.frameOptions(frame -> frame.sameOrigin());
                    } else {
                        headers.frameOptions(frame -> frame.deny());
                        // Deny-by-default. The salary-slip page is self-contained: one
                        // inline <style> block, no scripts, no images, no forms, no
                        // off-origin fetches of any kind - so everything except inline
                        // style can be switched off outright. JSON responses need none
                        // of it and are unaffected.
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'none'; style-src 'unsafe-inline'; "
                                        + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"));
                    }
                })
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
