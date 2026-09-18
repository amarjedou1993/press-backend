package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.email.*;
import com.presscard.press_accreditation.error.InvalidTokenException;
import com.presscard.press_accreditation.error.PasswordChangeException;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.validation.ValidPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Map;

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

    /**
     * The outcome of a verification link.
     *
     * ⚠️ A BOOLEAN RATHER THAN A MESSAGE TO PARSE.
     *
     * A second click succeeds — the endpoint is idempotent — but it succeeds
     * for a different reason, and the screen should say which. Left to read
     * `message`, the page would be matching on a French sentence, which
     * breaks the day somebody rewords it, and which an Arabic screen cannot
     * match at all.
     *
     * The flag is the fact; the sentence is for logs and for anything that
     * cannot translate.
     */
    public record VerifyEmailResponse(String message, boolean alreadyVerified) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "validation.requiredPassword")
            String currentPassword,

            @NotBlank(message = "validation.requiredPassword")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,100}$",
                    message = "validation.password")
            String newPassword
    ) {}

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

    @PostMapping("/verify-email")
    @Transactional
    public VerifyEmailResponse verifyEmail(@Valid @RequestBody TokenRequest request) {
        /*
         * ═══════════════════════════════════════════════════════════════
         * ⚠️ ASKED BEFORE CONSUMING, NOT CAUGHT AFTERWARDS.
         *
         * The first version wrapped consume() in a try and answered from the
         * catch. It read well and it did not work: consume() is @Transactional
         * and throws a RuntimeException, which marks the surrounding
         * transaction ROLLBACK-ONLY. Catching the exception does not unmark
         * it — so returning normally from the catch produced
         * UnexpectedRollbackException, a 500, and an incident number for a
         * person who had simply clicked a link twice.
         *
         * The exception never reached the client; the commit did.
         *
         * So the question is asked first, and consume() is only called when
         * it can succeed. Nothing throws, nothing is marked, and the reply is
         * the truth: this address is verified.
         * ═══════════════════════════════════════════════════════════════
         */
        User replayed = tokenService.ownerOfUsed(request.token(), EmailTokenType.VERIFY_EMAIL)
                .flatMap(userRepository::findById)
                .filter(User::isEmailVerified)
                .orElse(null);

        if (replayed != null) {
            /*
             * ⚠️ Logged under its own name. A handful means people clicking
             * twice; a flood means something is retrying the verification
             * e-mail, and the two are indistinguishable without it.
             */
            log.info("EMAIL_VERIFY_REPLAYED user={}", replayed.getEmail());
            return new VerifyEmailResponse("Votre adresse e-mail est déjà vérifiée.", true);
        }

        EmailToken token = tokenService.consume(request.token(), EmailTokenType.VERIFY_EMAIL);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Lien invalide ou expiré."));

        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(OffsetDateTime.now());
            userRepository.save(user);
            log.info("EMAIL_VERIFIED user={}", user.getEmail());
        }

        return new VerifyEmailResponse("Votre adresse e-mail a été vérifiée.", false);
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
                            user.getEmail(), user.getFullName(),
                            issued.rawToken(), user.getPreferredLocale());
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
        // ⚠️ A reset ESTABLISHES a local password on an account that may not
        // have had one. Without this flag the change-password endpoint below
        // would refuse to work afterwards.
        user.setHasPassword(true);
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

    /**
     * Change one's own password, from inside a session.
     *
     * ⚠️ @PreAuthorize IS NOT DECORATION HERE.
     *
     * This class has no class-level guard, because most of it must be
     * reachable without a session — login, forgot-password, a verification
     * link clicked from an inbox. SecurityConfig therefore permits
     * /api/auth/**.
     *
     * So without this annotation the endpoint is PUBLIC: `principal` arrives
     * null, orElseThrow blows up with a 500, and the only thing standing
     * between an anonymous caller and a password change is an exception.
     *
     * ⚠️ THE CURRENT PASSWORD IS RE-VERIFIED, and that is the point of the
     * endpoint rather than a formality. A valid token proves the session was
     * authenticated AT SOME POINT — it does not prove the person at the
     * keyboard is the account holder. An unattended screen in a newsroom is
     * exactly the case this defends against, and on this system the account
     * holds a press accreditation.
     */
    @PutMapping("/password")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    public ResponseEntity<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Principal principal) {

        User user = userRepository.findByEmail(principal.getName()).orElseThrow();

        // ⚠️ A Khidmaty account has no local password to change. Refused
        // clearly rather than silently setting one that nothing will ever
        // check.
        if (!user.isHasPassword() || user.getPasswordHash() == null) {
            throw new PasswordChangeException("validation.noLocalPassword");
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            log.warn("PASSWORD_CHANGE_REFUSED user={} reason=wrong_current", user.getId());
            // 401 — the SAME status a failed login gives, because it is the
            // same failure: a password that did not match.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ⚠️ Refused server-side too, not only in the dialog. A direct API
        // call would otherwise "succeed" while changing nothing, and the
        // audit line would record a change that did not happen.
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new PasswordChangeException("validation.passwordUnchanged");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setHasPassword(true);
        userRepository.save(user);

        log.info("PASSWORD_CHANGED user={}", user.getId());
        return ResponseEntity.noContent().build();
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