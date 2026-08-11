package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * The application's e-mail API: queue a message, never send one directly.
 *
 * Every method here writes an outbox row inside the CALLER'S transaction. So
 * a verification mail is queued in the same commit that creates the account —
 * if registration rolls back, the mail never existed; if SMTP is down,
 * registration still succeeds and the worker delivers later.
 *
 * Callers pass domain arguments, not subjects and bodies: the wording lives
 * with the template, in one place, in French.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger("EMAIL_AUDIT");

    private final EmailOutboxRepository outbox;
    private final AppProperties props;
    private final UserRepository userRepository;

    public EmailService(EmailOutboxRepository outbox, AppProperties props, UserRepository userRepository) {
        this.outbox = outbox;
        this.props = props;
        this.userRepository = userRepository;
    }

    /* ══ account lifecycle ════════════════════════════════════ */

    @Transactional
    public void sendVerification(String recipient, String fullName, String rawToken) {
        queue(recipient, EmailTemplate.VERIFY_EMAIL, Map.of(
                "fullName", fullName,
                "link", frontendLink("/verify-email", rawToken),
                "hours", 24));
    }

    @Transactional
    public void sendPasswordReset(String recipient, String fullName, String rawToken) {
        queue(recipient, EmailTemplate.PASSWORD_RESET, Map.of(
                "fullName", fullName,
                "link", frontendLink("/reset-password", rawToken),
                "minutes", 30));
    }

    /** Sent to the NEW address: only its owner can complete the change. */
    @Transactional
    public void sendEmailChangeConfirmation(String newEmail, String fullName, String rawToken) {
        queue(newEmail, EmailTemplate.EMAIL_CHANGE, Map.of(
                "fullName", fullName,
                "link", frontendLink("/confirm-email-change", rawToken),
                "hours", 2));
    }

    /**
     * Sent to the OLD address as a warning, carrying no link.
     * If the change was not the owner's doing, this is how they find out.
     */
    @Transactional
    public void sendEmailChangeNotice(String oldEmail, String fullName, String newEmail) {
        queue(oldEmail, EmailTemplate.EMAIL_CHANGE_NOTICE, Map.of(
                "fullName", fullName,
                "newEmail", newEmail));
    }

    /* ══ application lifecycle (weeks 3–6) ════════════════════ */

    @Transactional
    public void sendApplicationSubmitted(String recipient, String fullName, Long applicationId) {
        queue(recipient, EmailTemplate.APPLICATION_SUBMITTED, Map.of(
                "fullName", fullName,
                "applicationId", applicationId,
                "link", frontendUrl("/application")));
    }

    private void queue(String recipient, EmailTemplate template, Map<String, Object> payload) {
        if (!props.email().enabled()) {
            // Dev without MailHog: log the link so the flow stays testable.
            log.info("EMAIL_DISABLED template={} to={} payload={}", template, recipient, payload);
            return;
        }
        outbox.save(EmailOutbox.builder()
                .recipient(recipient)
                .template(template.name())
                .payload(payload)
                .status(EmailOutbox.Status.PENDING)
                .build());
        log.info("EMAIL_QUEUED template={} to={}", template, recipient);
    }

    // The commission's decisions must reach the candidate — a decision they do
// not know about is a decision they cannot act on, and the objection window
// runs regardless.

    /** Queued in the caller's transaction: no decision, no mail. */
    @Transactional
    public void sendDecisionNotice(Long candidateId, Long applicationId,
                                   String decision, String message) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            EmailTemplate template = switch (decision) {
                case "APPROVE" -> EmailTemplate.APPLICATION_ACCEPTED;
                case "REJECT" -> EmailTemplate.APPLICATION_REJECTED;
                default -> EmailTemplate.CORRECTION_REQUESTED;
            };
            queue(candidate.getEmail(), template, Map.of(
                    "fullName", candidate.getFullName(),
                    "applicationId", applicationId,
                    "message", message == null ? "" : message,
                    "link", frontendUrl("/application")));
        });
    }

    /** The 48-hour warning. A deadline nobody was told about is a trap. */
    @Transactional
    public void sendCorrectionDeadlineWarning(Long candidateId, Long applicationId,
                                              java.time.LocalDate deadline, int days) {
        userRepository.findById(candidateId).ifPresent(candidate ->
                queue(candidate.getEmail(), EmailTemplate.CORRECTION_DEADLINE_WARNING, Map.of(
                        "fullName", candidate.getFullName(),
                        "applicationId", applicationId,
                        "deadline", deadline.format(java.time.format.DateTimeFormatter
                                .ofPattern("d MMMM yyyy", java.util.Locale.FRENCH)),
                        "days", days,
                        "link", frontendUrl("/application"))));
    }

    @Transactional
    public void sendResubmissionConfirmation(Long candidateId, Long applicationId) {
        userRepository.findById(candidateId).ifPresent(candidate ->
                queue(candidate.getEmail(), EmailTemplate.CORRECTION_RESUBMITTED, Map.of(
                        "fullName", candidate.getFullName(),
                        "applicationId", applicationId,
                        "link", frontendUrl("/application"))));
    }

    @Transactional
    public void sendObjectionReceived(Long candidateId, Long applicationId, String reasonLabel) {
        userRepository.findById(candidateId).ifPresent(candidate ->
                queue(candidate.getEmail(), EmailTemplate.OBJECTION_RECEIVED, Map.of(
                        "fullName", candidate.getFullName(),
                        "applicationId", applicationId,
                        "reason", reasonLabel,
                        "link", frontendUrl("/application"))));
    }

    @Transactional
    public void sendCardIssued(Long candidateId, Long applicationId,
                               String cardNumber, java.time.LocalDate expiresAt) {
        userRepository.findById(candidateId).ifPresent(candidate ->
                queue(candidate.getEmail(), EmailTemplate.CARD_ISSUED, Map.of(
                        "fullName", candidate.getFullName(),
                        "cardNumber", cardNumber,
                        "expiresAt", expiresAt.format(java.time.format.DateTimeFormatter
                                .ofPattern("d MMMM yyyy", java.util.Locale.FRENCH)),
                        "link", frontendUrl("/application"))));
    }

    /** The Authority is told a proposal awaits it. */
    @Transactional
    public void sendRevocationProposed(Long proposalId, String cardNumber, String ground) {
        queue(props.email().commissionInbox(),
                EmailTemplate.REVOCATION_PROPOSED, Map.of(
                        "proposalId", proposalId,
                        "cardNumber", cardNumber,
                        "ground", ground,
                        "link", frontendUrl("/admin/cards/revocations")));
    }

    /** The holder is told, every time — rule 5. */
    @Transactional
    public void sendCardStatusChanged(Long holderId, String cardNumber,
                                      String status, String statusLabel, String reason) {
        userRepository.findById(holderId).ifPresent(holder ->
                queue(holder.getEmail(), switch (status) {
                    case "SUSPENDED" -> EmailTemplate.CARD_SUSPENDED;
                    case "REVOKED"   -> EmailTemplate.CARD_REVOKED;
                    default          -> EmailTemplate.CARD_REINSTATED;
                }, Map.of(
                        "fullName", holder.getFullName(),
                        "cardNumber", cardNumber,
                        "statusLabel", statusLabel,
                        "reason", reason)));
    }

    private String frontendLink(String path, String rawToken) {
        return frontendUrl(path) + "?token=" + rawToken;
    }

    private String frontendUrl(String path) {
        String base = props.cors().allowedOrigins().isEmpty()
                ? "http://localhost:3000"
                : props.cors().allowedOrigins().get(0);
        return base + path;
    }
}
