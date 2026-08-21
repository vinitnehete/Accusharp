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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT security. Two things worth being explicit about:
 *
 * <p><b>CSRF is disabled deliberately, not carelessly.</b> CSRF exists to stop
 * a browser automatically attaching a user's session cookie to a forged
 * cross-site request. This API has no session cookie - the client attaches a
 * bearer token itself on every call - so there is nothing for a forged
 * request to ride on. CSRF protection would be theatre here; it matters again
 * only if a future change starts authenticating via cookies.
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
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        if (h2ConsoleActive) {
            // The H2 console renders itself in a frame - only relaxed when the
            // h2 profile is actually active, same condition as the matcher above.
            http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        }

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
