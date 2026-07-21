package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.TestcontainersConfiguration;
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

/**
 * The week-1 demo, automated: real HTTP against the real schema
 * (Flyway + Testcontainers), through the real security filter chain.
 *
 * Covers: candidate registration, login, wrong-password 401, role
 * enforcement (candidate blocked from admin routes), bootstrap admin,
 * admin creating a reviewer, reviewer login.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    private static String candidateToken;
    private static String adminToken;

    @Test
    @Order(1)
    void candidate_canRegister_andReceivesToken() {
        var body = Map.of(
                "fullName", "Test Candidate",
                "email", "candidate@test.mr",
                "phone", "22123456",
                "password", "secret-password-1");

        ResponseEntity<Map> response =
                rest.postForEntity("/api/auth/register", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKeys("token", "role", "fullName");
        assertThat(response.getBody().get("role")).isEqualTo("CANDIDATE");
        candidateToken = (String) response.getBody().get("token");
    }

    @Test
    @Order(2)
    void duplicateEmail_isRejectedWith409() {
        var body = Map.of(
                "fullName", "Clone",
                "email", "candidate@test.mr",
                "phone", "22123456",
                "password", "secret-password-1");

        ResponseEntity<String> response =
                rest.postForEntity("/api/auth/register", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(3)
    void wrongPassword_isRejectedWith401() {
        var body = Map.of("email", "candidate@test.mr", "phone", "22123456", "password", "wrong-password");

        ResponseEntity<String> response =
                rest.postForEntity("/api/auth/login", body, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(4)
    void candidate_cannotAccessAdminEndpoints() {
        var request = new HttpEntity<>(
                Map.of("fullName", "X", "email", "x@test.mr", "password", "secret-password-1"),
                bearer(candidateToken));

        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users/reviewers", HttpMethod.POST, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @Order(5)
    void bootstrapAdmin_canLogin() {
        // Credentials come from app.admin.* in application-dev.yaml.
        var body = Map.of(
                "email", "admin@press-accreditation.local",
                "password", "admin_dev_password");

        ResponseEntity<Map> response =
                rest.postForEntity("/api/auth/login", body, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("role")).isEqualTo("SUPER_ADMIN");
        adminToken = (String) response.getBody().get("token");
    }

    @Test
    @Order(6)
    void admin_canCreateReviewer_whoCanThenLogin() {
        var create = new HttpEntity<>(
                Map.of("fullName", "Reviewer One",
                       "email", "reviewer1@test.mr",
                       "password", "reviewer-pass-1"),
                bearer(adminToken));

        ResponseEntity<Map> created = rest.exchange(
                "/api/admin/users/reviewers", HttpMethod.POST, create, Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("role")).isEqualTo("REVIEWER");

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                Map.of("email", "reviewer1@test.mr", "password", "reviewer-pass-1"),
                Map.class);

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody().get("role")).isEqualTo("REVIEWER");
    }

    @Test
    @Order(7)
    void missingToken_onProtectedRoute_is401() {
        ResponseEntity<String> response = rest.exchange(
                "/api/admin/users/reviewers", HttpMethod.POST,
                new HttpEntity<>(Map.of()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
