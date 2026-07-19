package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the brute-force limiter. Runs in its OWN Spring context with a
 * tiny limit (3/min) via @TestPropertySource — the other integration tests
 * keep the generous dev limit and never trip it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "app.security.auth-requests-per-minute=3")
class AuthRateLimitTest {

    @Autowired TestRestTemplate rest;

    @Test
    void fourthAttemptWithinTheWindow_is429() {
        Map<String, String> badLogin =
                Map.of("email", "nobody@test.mr", "password", "wrong-password");

        // Three attempts: normal 401s — the limiter stays out of the way.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> r =
                    rest.postForEntity("/api/auth/login", badLogin, String.class);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        // Fourth within the minute: blocked before the login machinery.
        ResponseEntity<String> blocked =
                rest.postForEntity("/api/auth/login", badLogin, String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
