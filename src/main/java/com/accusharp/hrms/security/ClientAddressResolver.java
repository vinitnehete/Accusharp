package com.accusharp.hrms.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Works out who is actually calling, for the purpose of IP-based throttling.
 *
 * <p><b>Why this class exists.</b> {@code AuthController} previously trusted
 * {@code X-Forwarded-For} unconditionally, on the stated assumption that the
 * app "sits behind its own reverse proxy". Nothing enforced that assumption,
 * and {@link LoginRateLimiter} keys its sliding window on the returned value -
 * so any client able to reach the service directly could send a different
 * forged {@code X-Forwarded-For} on every request and land each attempt in its
 * own bucket. The brute-force brake was effectively off for exactly the
 * attacker it existed to stop. Account-level lockout still applied, but that
 * only protects a single account; password-spraying across many accounts was
 * unthrottled.
 *
 * <p><b>The rule.</b> A forwarding header is evidence only if the hop that
 * delivered it is one we put there. So:
 * <ul>
 *   <li>If the direct peer is not a configured trusted proxy, the header is
 *       ignored entirely and the socket address is used. A client talking
 *       straight to the service cannot influence its own bucket.</li>
 *   <li>If it is trusted, the chain is walked <em>right to left</em>, skipping
 *       further trusted hops, and the first untrusted address is the caller.
 *       Reading left-to-right - the previous behaviour - takes the end of the
 *       chain a client can freely prepend to.</li>
 * </ul>
 *
 * <p><b>The default is to trust nothing</b> ({@code app.security.trusted-proxies}
 * empty), so an unconfigured deployment is throttled by real socket address
 * rather than by a spoofable header. Set it to the proxy's address once one is
 * actually in front - see application.properties.
 */
@Component
@Slf4j
public class ClientAddressResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final List<IpAddressMatcher> trustedProxies;

    public ClientAddressResolver(
            @Value("${app.security.trusted-proxies:}") List<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream()
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(IpAddressMatcher::new)
                .toList();
        if (this.trustedProxies.isEmpty()) {
            log.info("client-address.resolver trustedProxies=none - X-Forwarded-For will be ignored "
                    + "and login throttling will key on the direct socket address");
        } else {
            log.info("client-address.resolver trustedProxies={}", trustedProxies);
        }
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrusted(remoteAddr)) {
            return remoteAddr;
        }

        String forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }

        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!hop.isEmpty() && !isTrusted(hop)) {
                return hop;
            }
        }
        // Every hop in the chain is one of ours - nothing better to key on.
        return remoteAddr;
    }

    private boolean isTrusted(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxies) {
            try {
                if (matcher.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException notAnIpAddress) {
                // A malformed hop in a forwarded chain is untrusted, not fatal.
                return false;
            }
        }
        return false;
    }
}
