package com.accusharp.hrms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the H2 console guard the audit flagged: {@code
 * /h2-console/**} being {@code permitAll} had no condition of its own, so
 * activating the "h2" Spring profile anywhere reachable would expose an
 * unauthenticated SQL console regardless of {@code spring.h2.console.enabled}.
 * This runs under the test suite's normal (non-h2) profile, where the
 * matcher must not exist at all - {@code /h2-console/**} should require
 * authentication exactly like any other endpoint, not be waved through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class H2ConsoleSecurityTest {

    @Autowired private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void h2ProfileIsNotActiveInTheTestSuite() {
        // Sanity check the premise below - if this ever starts failing because
        // the test suite's profile setup changed, the assertion after it would
        // otherwise silently test the wrong thing.
        assertThat(environment.acceptsProfiles(Profiles.of("h2"))).isFalse();
    }

    @Test
    void h2ConsoleIsNotPermittedWithoutTheH2Profile() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + environment.getProperty("local.server.port")
                        + "/h2-console/login.jsp"))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
