package com.presscard.press_accreditation.security;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test — no Spring, no files, no Docker. Possible because
 * JwtService takes keys in its constructor: we generate a throwaway
 * keypair in memory. Runs in milliseconds.
 */
class JwtServiceTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
    }

    private JwtService service(Duration ttl) {
        var props = new AppProperties(
                new AppProperties.Jwt("test-issuer", null, null, ttl),
                null, null, null, null, null, null, null, null, null, null);
        return new JwtService(keyPair.getPrivate(), keyPair.getPublic(), props);
    }

    private User sampleUser() {
        return User.builder()
                .id(42L).email("amar@test.mr").role(UserRole.CANDIDATE)
                .fullName("Amar").build();
    }

    @Test
    void roundTrip_validToken_claimsIntact() {
        JwtService svc = service(Duration.ofHours(1));

        String token = svc.generateToken(sampleUser());
        Claims claims = svc.parse(token);

        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("email", String.class)).isEqualTo("amar@test.mr");
        assertThat(claims.get("role", String.class)).isEqualTo("CANDIDATE");
        assertThat(claims.getIssuer()).isEqualTo("test-issuer");
    }

    @Test
    void expiredToken_isRejected() {
        // Negative TTL: the token is born expired.
        JwtService svc = service(Duration.ofSeconds(-5));
        String token = svc.generateToken(sampleUser());

        assertThatThrownBy(() -> svc.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedToken_isRejected() {
        JwtService svc = service(Duration.ofHours(1));
        String token = svc.generateToken(sampleUser());

        // Flip a character in the payload segment: signature check must fail.
        String tampered = token.substring(0, token.length() - 10)
                + "X" + token.substring(token.length() - 9);

        assertThatThrownBy(() -> svc.parse(tampered)).isInstanceOf(JwtException.class);
    }
}
