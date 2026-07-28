package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.error.InvalidTokenException;
import com.presscard.press_accreditation.error.TooManyRequestsException;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The token service guards password reset, so each of its security properties
 * gets a test. These are not "does it work" tests — they are "can it be
 * abused" tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@ActiveProfiles("test")          // ← add
class EmailTokenServiceTest {

    @Autowired EmailTokenService service;
    @Autowired EmailTokenRepository repository;
    @Autowired UserRepository userRepository;

    private User user() {
        return userRepository.save(User.builder()
                .email("tok-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(UserRole.CANDIDATE)
                .fullName("Token Test").phone("22123456")
                .build());
    }

    /* ── the raw token never reaches the database ── */

    @Test
    void onlyTheHashIsStored_neverTheRawToken() {
        User u = user();
        var issued = service.issue(u.getId(), EmailTokenType.VERIFY_EMAIL);

        EmailToken stored = repository.findById(issued.record().getId()).orElseThrow();

        assertThat(stored.getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(stored.getTokenHash()).hasSize(64);          // SHA-256 hex
        assertThat(stored.getTokenHash()).isEqualTo(EmailTokenService.hash(issued.rawToken()));
    }

    /* ── single use ── */

    @Test
    void aTokenWorksExactlyOnce() {
        User u = user();
        var issued = service.issue(u.getId(), EmailTokenType.VERIFY_EMAIL);

        EmailToken consumed = service.consume(issued.rawToken(), EmailTokenType.VERIFY_EMAIL);
        assertThat(consumed.getUsedAt()).isNotNull();

        // Replaying the same link does nothing.
        assertThatThrownBy(() ->
                service.consume(issued.rawToken(), EmailTokenType.VERIFY_EMAIL))
                .isInstanceOf(InvalidTokenException.class);
    }

    /* ── issuing invalidates the previous link ── */

    @Test
    void issuingAgain_invalidatesTheEarlierToken() {
        User u = user();
        var first = service.issue(u.getId(), EmailTokenType.PASSWORD_RESET);
        var second = service.issue(u.getId(), EmailTokenType.PASSWORD_RESET);

        // The old link is dead — a reset the user did not request is
        // neutralised the moment they request one themselves.
        assertThatThrownBy(() ->
                service.consume(first.rawToken(), EmailTokenType.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);

        assertThat(service.consume(second.rawToken(), EmailTokenType.PASSWORD_RESET))
                .isNotNull();
    }

    /* ── a token cannot be used for the wrong purpose ── */

    @Test
    void aVerificationLinkCannotResetAPassword() {
        User u = user();
        var issued = service.issue(u.getId(), EmailTokenType.VERIFY_EMAIL);

        assertThatThrownBy(() ->
                service.consume(issued.rawToken(), EmailTokenType.PASSWORD_RESET))
                .isInstanceOf(InvalidTokenException.class);

        // …and the mismatch did not consume it.
        assertThat(service.consume(issued.rawToken(), EmailTokenType.VERIFY_EMAIL))
                .isNotNull();
    }

    /* ── expiry ── */

    @Test
    void anExpiredTokenIsRejected() {
        User u = user();
        var issued = service.issue(u.getId(), EmailTokenType.VERIFY_EMAIL);

        EmailToken token = repository.findById(issued.record().getId()).orElseThrow();
        token.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        repository.save(token);

        assertThatThrownBy(() ->
                service.consume(issued.rawToken(), EmailTokenType.VERIFY_EMAIL))
                .isInstanceOf(InvalidTokenException.class);
    }

    /* ── unknown tokens ── */

    @Test
    void anUnknownTokenIsRejected() {
        assertThatThrownBy(() ->
                service.consume("not-a-real-token", EmailTokenType.VERIFY_EMAIL))
                .isInstanceOf(InvalidTokenException.class);
    }

    /* ── mailbox flooding ── */

    @Test
    void requestsArePerUserRateLimited() {
        User u = user();
        for (int i = 0; i < 5; i++) {
            service.issue(u.getId(), EmailTokenType.PASSWORD_RESET);
        }

        assertThatThrownBy(() ->
                service.issue(u.getId(), EmailTokenType.PASSWORD_RESET))
                .isInstanceOf(TooManyRequestsException.class);
    }

    /* ── TTLs reflect risk ── */

    @Test
    void resetLinksExpireSoonerThanVerificationLinks() {
        User u = user();
        var verify = service.issue(u.getId(), EmailTokenType.VERIFY_EMAIL);
        var reset = service.issue(u.getId(), EmailTokenType.PASSWORD_RESET);

        assertThat(reset.record().getExpiresAt())
                .isBefore(verify.record().getExpiresAt());
    }
}
