package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * Queues a message. Never sends one.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ AN E-MAIL HAS NO REQUEST TO READ A LOCALE FROM.
 *
 * Every translation in the interface reads the language from the request: a
 * person is looking at a page. This is composed by a scheduled job, hours
 * later, for somebody who is not there.
 *
 * So each row carries its OWN locale, fixed at queue time from the
 * recipient's stored preference. Two consequences:
 *
 *   · A retry reproduces the first attempt rather than a different language.
 *   · Someone who switches preference afterwards does not rewrite the
 *     language of a decision already taken.
 *
 * ⚠️ AND NOTHING IN THE PAYLOAD IS PRE-FORMATTED.
 *
 * Dates used to arrive as "15 mars 2026", formatted here with Locale.FRENCH —
 * a French date inside an Arabic message, and unfixable by the renderer
 * because the original value was gone. Dates now travel as ISO strings and
 * the template formats them in the row's own locale.
 *
 * Labels travel as CODES for the same reason, where a code exists.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger("EMAIL_AUDIT");

    /** Staff notifications go to the Authority's working language. */
    private static final String STAFF_LOCALE = "fr";

    private final EmailOutboxRepository outbox;
    private final AppProperties props;
    private final UserRepository userRepository;

    public EmailService(EmailOutboxRepository outbox, AppProperties props,
                        UserRepository userRepository) {
        this.outbox = outbox;
        this.props = props;
        this.userRepository = userRepository;
    }

    /* ══ account lifecycle ════════════════════════════════════ */

    /**
     * ⚠️ The locale is passed in rather than looked up: this is sent DURING
     * registration, in the same transaction that creates the user, and a
     * lookup would race the insert.
     */
    @Transactional
    public void sendVerification(String recipient, String fullName,
                                 String rawToken, String locale) {
        queue(recipient, EmailTemplate.VERIFY_EMAIL, locale, payload(
                "fullName", fullName,
                "link", frontendLink(locale, "/verify-email", rawToken),
                "hours", 24));
    }

    @Transactional
    public void sendPasswordReset(String recipient, String fullName, String rawToken) {
        String locale = localeOf(recipient);
        queue(recipient, EmailTemplate.PASSWORD_RESET, locale, payload(
                "fullName", fullName,
                "link", frontendLink(locale, "/reset-password", rawToken),
                "minutes", 30));
    }

    /** Sent to the NEW address: only its owner can complete the change. */
    @Transactional
    public void sendEmailChangeConfirmation(String newEmail, String fullName,
                                            String rawToken, String locale) {
        queue(newEmail, EmailTemplate.EMAIL_CHANGE, locale, payload(
                "fullName", fullName,
                "link", frontendLink(locale, "/confirm-email-change", rawToken),
                "hours", 2));
    }

    /**
     * Sent to the OLD address as a warning, carrying no link.
     * If the change was not the owner's doing, this is how they find out.
     */
    @Transactional
    public void sendEmailChangeNotice(String oldEmail, String fullName,
                                      String newEmail, String locale) {
        queue(oldEmail, EmailTemplate.EMAIL_CHANGE_NOTICE, locale, payload(
                "fullName", fullName,
                "newEmail", newEmail));
    }

    /* ══ application lifecycle ════════════════════════════════ */

    @Transactional
    public void sendApplicationSubmitted(String recipient, String fullName, Long applicationId) {
        String locale = localeOf(recipient);
        queue(recipient, EmailTemplate.APPLICATION_SUBMITTED, locale, payload(
                "fullName", fullName,
                "applicationId", applicationId,
                "link", frontendUrl(locale, "/application")));
    }

    /**
     * The commission's decisions must reach the candidate — a decision they do
     * not know about is a decision they cannot act on, and the objection
     * window runs regardless.
     *
     * Queued in the caller's transaction: no decision, no mail.
     *
     * ⚠️ `message` is the member's own justification — FREE TEXT, in whichever
     * language they wrote it, and never translated. The template must render
     * it with dir="auto", exactly as the screens do.
     */
    @Transactional
    public void sendDecisionNotice(Long candidateId, Long applicationId,
                                   String decision, String message) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            EmailTemplate template = switch (decision) {
                case "APPROVE" -> EmailTemplate.APPLICATION_ACCEPTED;
                case "REJECT" -> EmailTemplate.APPLICATION_REJECTED;
                default -> EmailTemplate.CORRECTION_REQUESTED;
            };
            String locale = localeOf(candidate);
            queue(candidate.getEmail(), template, locale, payload(
                    "fullName", candidate.getFullName(),
                    "applicationId", applicationId,
                    "message", message == null ? "" : message,
                    "link", frontendUrl(locale, "/application")));
        });
    }

    /** The 48-hour warning. A deadline nobody was told about is a trap. */
    @Transactional
    public void sendCorrectionDeadlineWarning(Long candidateId, Long applicationId,
                                              LocalDate deadline, int days) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            String locale = localeOf(candidate);
            queue(candidate.getEmail(), EmailTemplate.CORRECTION_DEADLINE_WARNING, locale, payload(
                    "fullName", candidate.getFullName(),
                    "applicationId", applicationId,
                    // ⚠️ ISO, not "15 mars 2026". The template formats it in
                    // the row's own locale; a pre-formatted French date could
                    // not be unpicked.
                    "deadline", deadline.toString(),
                    "days", days,
                    "link", frontendUrl(locale, "/application")));
        });
    }

    @Transactional
    public void sendResubmissionConfirmation(Long candidateId, Long applicationId) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            String locale = localeOf(candidate);
            queue(candidate.getEmail(), EmailTemplate.CORRECTION_RESUBMITTED, locale, payload(
                    "fullName", candidate.getFullName(),
                    "applicationId", applicationId,
                    "link", frontendUrl(locale, "/application")));
        });
    }

    /**
     * ⚠️ The ground travels as a CODE, not a label.
     *
     * It used to be reason.getLabelFr() — a French phrase inside what may be
     * an Arabic message. The template holds both wordings under the code.
     */
    @Transactional
    public void sendObjectionReceived(Long candidateId, Long applicationId, String reasonCode) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            String locale = localeOf(candidate);
            queue(candidate.getEmail(), EmailTemplate.OBJECTION_RECEIVED, locale, payload(
                    "fullName", candidate.getFullName(),
                    "applicationId", applicationId,
                    "reasonCode", reasonCode,
                    "link", frontendUrl(locale, "/application")));
        });
    }

    @Transactional
    public void sendCardIssued(Long candidateId, Long applicationId,
                               String cardNumber, LocalDate expiresAt) {
        userRepository.findById(candidateId).ifPresent(candidate -> {
            String locale = localeOf(candidate);
            queue(candidate.getEmail(), EmailTemplate.CARD_ISSUED, locale, payload(
                    "fullName", candidate.getFullName(),
                    "cardNumber", cardNumber,
                    "expiresAt", expiresAt.toString(),
                    "link", frontendUrl(locale, "/application")));
        });
    }

    /** The Authority is told a proposal awaits it. Staff mail: French. */
    @Transactional
    public void sendRevocationProposed(Long proposalId, String cardNumber, String groundCode) {
        queue(props.email().commissionInbox(),
                EmailTemplate.REVOCATION_PROPOSED, STAFF_LOCALE, payload(
                        "proposalId", proposalId,
                        "cardNumber", cardNumber,
                        "groundCode", groundCode,
                        "link", frontendUrl(STAFF_LOCALE, "/admin/cards/revocations")));
    }

    /**
     * ⚠️ `reason` MAY BE NULL — a reinstatement carries none.
     *
     * The previous version used Map.of, which throws NullPointerException on
     * a null value. Reinstating a suspended card would have failed at the
     * e-mail rather than at anything meaningful, inside the caller's
     * transaction, rolling back the reinstatement itself.
     *
     * payload() uses HashMap and tolerates nulls.
     */
    @Transactional
    public void sendCardStatusChanged(Long holderId, String cardNumber,
                                      String status, String reason) {
        userRepository.findById(holderId).ifPresent(holder -> {
            String locale = localeOf(holder);
            queue(holder.getEmail(), switch (status) {
                case "SUSPENDED" -> EmailTemplate.CARD_SUSPENDED;
                case "REVOKED"   -> EmailTemplate.CARD_REVOKED;
                default          -> EmailTemplate.CARD_REINSTATED;
            }, locale, payload(
                    "fullName", holder.getFullName(),
                    "cardNumber", cardNumber,
                    // The status CODE; the template holds both labels.
                    "status", status,
                    // Free text from the Authority, or absent.
                    "reason", reason));
        });
    }

    /* ══ internals ════════════════════════════════════════════ */

    private void queue(String recipient, EmailTemplate template,
                       String locale, Map<String, Object> payload) {
        if (!props.email().enabled()) {
            // Dev without MailHog: log the link so the flow stays testable.
            log.info("EMAIL_DISABLED template={} to={} locale={} payload={}",
                    template, recipient, locale, payload);
            return;
        }
        outbox.save(EmailOutbox.builder()
                .recipient(recipient)
                .template(template.name())
                .locale(locale)
                .payload(payload)
                .status(EmailOutbox.Status.PENDING)
                .build());
        log.info("EMAIL_QUEUED template={} to={} locale={}", template, recipient, locale);
    }

    /**
     * A mutable map that tolerates nulls, unlike Map.of.
     *
     * ⚠️ Map.of throws on a null VALUE, and several payloads legitimately
     * carry one — a reinstatement has no reason, an unexplained suspension
     * has no note. A message failing to queue would roll back the act it was
     * announcing.
     */
    private static Map<String, Object> payload(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** The stored preference, or the default if the person is unknown. */
    private String localeOf(User user) {
        String locale = user.getPreferredLocale();
        return locale == null || locale.isBlank() ? "ar" : locale;
    }

    private String localeOf(String email) {
        return userRepository.findByEmail(email)
                .map(this::localeOf)
                .orElse("ar");
    }

    /**
     * ⚠️ THE LINK CARRIES THE LOCALE.
     *
     * Every route now lives under /[locale]. A link to "/verify-email" lands
     * on a path that does not exist: the proxy would redirect it, losing the
     * query string in some configurations — and the token IS the query string.
     *
     * An Arabic reader also expects an Arabic page when they click.
     */
    private String frontendLink(String locale, String path, String rawToken) {
        return frontendUrl(locale, path) + "?token=" + rawToken;
    }

    private String frontendUrl(String locale, String path) {
        String base = props.cors().allowedOrigins().isEmpty()
                ? "http://localhost:3000"
                : props.cors().allowedOrigins().get(0);
        return base + "/" + locale + path;
    }
}
