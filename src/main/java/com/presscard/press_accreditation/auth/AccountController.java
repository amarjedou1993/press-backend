package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.email.*;
import com.presscard.press_accreditation.error.InvalidTokenException;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import com.presscard.press_accreditation.validation.ValidPassword;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * The three e-mail-proof flows: verification, password reset, address change.
 *
 * ANTI-ENUMERATION runs through all of them. "Forgot password" and "resend
 * verification" answer 200 with the same message whether or not the address
 * exists — otherwise the endpoint becomes a free tool for discovering which
 * journalists hold accounts, which for a press regulator is a disclosure that
 * matters.
 *
 * Everything except the change request is public: a person who has forgotten
 * their password cannot authenticate first.
 */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger("ACCOUNT_AUDIT");

    /* ── contracts ── */

    public record EmailRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @ValidPassword String newPassword
    ) {}

    public record TokenRequest(@NotBlank String token) {}

    public record ChangeEmailRequest(@NotBlank @Email String newEmail) {}

    public record MessageResponse(String message) {}

    private final UserRepository userRepository;
    private final EmailTokenService tokenService;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    public AccountController(UserRepository userRepository,
                             EmailTokenService tokenService,
                             EmailService emailService,
                             PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    /* ══ 1. e-mail verification ═══════════════════════════════ */

    /** Consume a verification link. */
    @PostMapping("/verify-email")
    @Transactional
    public MessageResponse verifyEmail(@Valid @RequestBody TokenRequest request) {
        EmailToken token = tokenService.consume(request.token(), EmailTokenType.VERIFY_EMAIL);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Lien invalide ou expiré."));

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(OffsetDateTime.now());
            userRepository.save(user);
            log.info("EMAIL_VERIFIED user={}", user.getEmail());
        }

        return new MessageResponse("Votre adresse e-mail a été vérifiée.");
    }

    /** Re-send the verification link. Always 200 — see the class javadoc. */
    @PostMapping("/resend-verification")
    @Transactional
    public MessageResponse resendVerification(@Valid @RequestBody EmailRequest request) {
        userRepository.findByEmail(AuthService.normalize(request.email()))
                .filter(u -> !u.isEmailVerified())
                .ifPresent(user -> {
                    var issued = tokenService.issue(user.getId(), EmailTokenType.VERIFY_EMAIL);
                    emailService.sendVerification(
                            user.getEmail(), user.getFullName(), issued.rawToken(), user.getPreferredLocale());
                });

        return new MessageResponse(
                "Si un compte non vérifié existe pour cette adresse, un e-mail a été envoyé.");
    }

    /* ══ 2. password reset ════════════════════════════════════ */

    /** Request a reset link. Always 200, whether or not the account exists. */
    @PostMapping("/forgot-password")
    @Transactional
    public MessageResponse forgotPassword(@Valid @RequestBody EmailRequest request) {
        userRepository.findByEmail(AuthService.normalize(request.email()))
                .filter(User::isEnabled)
                .ifPresent(user -> {
                    var issued = tokenService.issue(user.getId(), EmailTokenType.PASSWORD_RESET);
                    emailService.sendPasswordReset(
                            user.getEmail(), user.getFullName(), issued.rawToken());
                    log.info("PASSWORD_RESET_REQUESTED user={}", user.getEmail());
                });

        return new MessageResponse(
                "Si un compte existe pour cette adresse, un e-mail a été envoyé.");
    }

    /** Consume a reset link and set the new password. */
    @PostMapping("/reset-password")
    @Transactional
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        EmailToken token = tokenService.consume(request.token(), EmailTokenType.PASSWORD_RESET);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Lien invalide ou expiré."));

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // A reset proves control of the mailbox — so it also verifies it.
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(OffsetDateTime.now());
            userRepository.save(user);
        }

        log.info("PASSWORD_RESET_COMPLETED user={}", user.getEmail());
        return new MessageResponse(
                "Votre mot de passe a été modifié. Vous pouvez vous connecter.");
    }

    /* ══ 3. e-mail change ═════════════════════════════════════ */

    /**
     * Request a change. The link goes to the NEW address (only its owner can
     * complete it); a warning goes to the OLD one (so a hijack is visible).
     * The account keeps its current address until confirmation.
     */
    @PostMapping("/change-email")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public MessageResponse requestEmailChange(@Valid @RequestBody ChangeEmailRequest request,
                                              Principal principal) {
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();
        String newEmail = AuthService.normalize(request.newEmail());

        if (newEmail.equals(user.getEmail())) {
            return new MessageResponse("Cette adresse est déjà la vôtre.");
        }
        if (userRepository.existsByEmail(newEmail)) {
            // Deliberately identical to the success message: this endpoint
            // must not become a way to test which addresses are registered.
            return new MessageResponse(
                    "Si cette adresse est disponible, un e-mail de confirmation a été envoyé.");
        }

        var issued = tokenService.issue(user.getId(), EmailTokenType.EMAIL_CHANGE, newEmail);
//        emailService.sendEmailChangeConfirmation(newEmail, user.getFullName(), issued.rawToken());
//        emailService.sendEmailChangeNotice(user.getEmail(), user.getFullName(), newEmail);
        // ⚠️ The holder's stored language, not the interface's: this is
        // e-mail, and it may be read hours later.
        String locale = user.getPreferredLocale();

        emailService.sendEmailChangeConfirmation(
                newEmail, user.getFullName(), issued.rawToken(), locale);
        emailService.sendEmailChangeNotice(
                user.getEmail(), user.getFullName(), newEmail, locale);

        log.info("EMAIL_CHANGE_REQUESTED user={} newEmail={}", user.getEmail(), newEmail);
        return new MessageResponse(
                "Si cette adresse est disponible, un e-mail de confirmation a été envoyé.");
    }

    /** Consume the confirmation link and switch the address. */
    @PostMapping("/confirm-email-change")
    @Transactional
    public MessageResponse confirmEmailChange(@Valid @RequestBody TokenRequest request) {
        EmailToken token = tokenService.consume(request.token(), EmailTokenType.EMAIL_CHANGE);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Lien invalide ou expiré."));

        String newEmail = token.getNewEmail();
        // Re-check at consumption: the address may have been taken meanwhile.
        if (newEmail == null || userRepository.existsByEmail(newEmail)) {
            throw new InvalidTokenException(
                    "Cette adresse n'est plus disponible. Recommencez la demande.");
        }

        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerified(true);      // proven by consuming this link
        user.setEmailVerifiedAt(OffsetDateTime.now());
        userRepository.save(user);

        log.info("EMAIL_CHANGED user={} -> {}", oldEmail, newEmail);
        return new MessageResponse(
                "Votre adresse e-mail a été modifiée. Reconnectez-vous avec la nouvelle adresse.");
    }

    /* ══ status ═══════════════════════════════════════════════ */

    /** Lets the UI show a "verify your address" banner without guessing. */
    @GetMapping("/verification-status")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> verificationStatus(Principal principal) {
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();
        return Map.of(
                "email", user.getEmail(),
                "verified", user.isEmailVerified());
    }
}
