package com.accusharp.hrms.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the cookie the refresh token travels in.
 *
 * <p>The refresh token is the high-value credential in this system: it is
 * valid for seven days and can be exchanged for an unlimited series of access
 * tokens. It used to be returned in the login JSON body and kept in browser
 * {@code localStorage}, which meant any successful XSS anywhere in the SPA
 * handed an attacker a week-long session. It is now issued only as a cookie
 * that JavaScript cannot read at all.
 *
 * <p>Four properties do the work, and each is load-bearing:
 * <ul>
 *   <li><b>HttpOnly</b> - {@code document.cookie} cannot see it, so stealing
 *       it is no longer a consequence of XSS.</li>
 *   <li><b>Secure</b> - never sent over plaintext HTTP. Defaults to
 *       {@code true}; the {@code h2} dev profile and the test profile turn it
 *       off, because a browser will not store a Secure cookie from a plain
 *       {@code http://} origin and local development has no TLS.</li>
 *   <li><b>SameSite=Strict</b> - this is the CSRF control for the two
 *       endpoints that consume the cookie (see {@link
 *       com.accusharp.hrms.config.SecurityConfig} for why the rest of the API
 *       needs none). A cross-site page cannot make the browser attach this
 *       cookie at all, so a forged refresh or logout never reaches a valid
 *       token.</li>
 *   <li><b>Path=/api/auth</b> - the browser attaches it only to the four auth
 *       endpoints, so the credential is absent from the ~119 business
 *       requests that have no use for it. Narrows what a proxy log, a crash
 *       dump or a mis-scoped debug tool can ever capture.</li>
 * </ul>
 *
 * <p>The access token deliberately does <em>not</em> travel this way - see
 * {@link com.accusharp.hrms.config.SecurityConfig}.
 */
@Component
public class RefreshTokenCookie {

    /** Cookie name; also what {@code AuthController} reads with {@code @CookieValue}. */
    public static final String NAME = "refreshToken";

    /** Scoped so the cookie is never attached to business endpoints. */
    private static final String PATH = "/api/auth";

    private final boolean secure;
    private final String sameSite;
    private final long maxAgeSeconds;

    public RefreshTokenCookie(
            @Value("${app.auth.cookie.secure:true}") boolean secure,
            @Value("${app.auth.cookie.same-site:Strict}") String sameSite,
            @Value("${jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays) {
        this.secure = secure;
        this.sameSite = sameSite;
        // Matches the token's own server-side lifetime, so the browser stops
        // sending a cookie at the same moment the database stops honouring it.
        this.maxAgeSeconds = refreshTokenExpiryDays * 86400;
    }

    public ResponseCookie issue(String rawToken) {
        return base(rawToken).maxAge(maxAgeSeconds).build();
    }

    /** Same attributes with a zero max-age - the only way a browser reliably drops a cookie. */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(PATH);
    }
}
