package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityHardeningIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired UserRepository userRepository;

    @Test
    @Order(1)
    void disabledAccount_existingTokenDiesInstantly() {
        // Register → valid token.
        ResponseEntity<Map> registered = rest.postForEntity("/api/auth/register",
                Map.of("fullName", "Revocation Case",
                       "email", "revoked@test.mr",
                        "phone", "22123456",
                       "password", "secret-password-1"),
                Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token = (String) registered.getBody().get("token");

        // Token works: an authenticated request to a nonexistent API path
        // passes security and reaches routing → 404 (NOT 401).
        assertThat(callWithToken(token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Admin-side disable (repository stands in for the future endpoint).
        var user = userRepository.findByEmail("revoked@test.mr").orElseThrow();
        user.setEnabled(false);
        userRepository.save(user);

        // Same token, next request: dead. No blacklist, no waiting for expiry.
        assertThat(callWithToken(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(2)
    void emailNormalization_caseVariantsAreOneIdentity() {
        ResponseEntity<Map> registered = rest.postForEntity("/api/auth/register",
                Map.of("fullName", "Case Test",
                       "email", "CASE@Test.MR",
                        "phone", "22123456",
                       "password", "secret-password-1"),
                Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Lowercase login succeeds against the normalized identity.
        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                Map.of("email", "case@test.mr", "password", "secret-password-1"),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        // And the uppercase variant cannot register a second account.
        ResponseEntity<String> duplicate = rest.postForEntity("/api/auth/register",
                Map.of("fullName", "Clone",
                       "email", "CASE@TEST.MR",
                        "phone", "22123456",
                       "password", "secret-password-1"),
                String.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(3)
    void bootstrapAdmin_isExactlyOne() {
        assertThat(userRepository.countByRole(UserRole.SUPER_ADMIN)).isEqualTo(1);
    }

    private ResponseEntity<String> callWithToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange("/api/nonexistent-probe", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
