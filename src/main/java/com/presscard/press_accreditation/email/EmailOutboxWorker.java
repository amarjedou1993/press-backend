package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.config.AppProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Drains the outbox.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ HTML, NOT PLAIN TEXT — AND THE REASON IS THE LINKS.
 *
 * Every message here carries a URL. In a plain-text Arabic paragraph a Latin
 * URL BIDI-REORDERS: the client cannot be told to isolate it, and readers see
 * a mangled address they cannot copy. Some clients even break the trailing
 * token off the end.
 *
 * A verification link that arrives unreadable is a candidate who cannot use
 * the system at all. HTML lets the URL sit in <span dir="ltr"> and stay
 * intact.
 *
 * The message is sent MULTIPART: a plain-text part for clients that refuse
 * HTML, and the HTML part for everyone else. The text part still reorders in
 * Arabic — nothing can prevent that — but it is the fallback, not the
 * default.
 * ───────────────────────────────────────────────────────────────────────
 */
@Component
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
public class EmailOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger("EMAIL_WORKER");

    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 20;

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mailSender;
    private final EmailRenderer renderer;
    private final AppProperties props;

    public EmailOutboxWorker(EmailOutboxRepository outbox,
                             JavaMailSender mailSender,
                             EmailRenderer renderer,
                             AppProperties props) {
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.renderer = renderer;
        this.props = props;
    }

    /**
     * Every 15 seconds. Frequent enough that a verification link arrives
     * while the candidate is still on the page; rare enough to be invisible.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
    @Transactional
    public void dispatch() {
        List<EmailOutbox> batch = outbox.lockNextBatch(MAX_ATTEMPTS, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        log.debug("Dispatching {} queued e-mail(s)", batch.size());
        for (EmailOutbox message : batch) {
            send(message);
        }
    }

    private void send(EmailOutbox message) {
        message.setAttempts(message.getAttempts() + 1);
        try {
            EmailTemplate template = EmailTemplate.valueOf(message.getTemplate());

            // ⚠️ The locale comes from the ROW, not from anywhere else. It was
            // fixed when the message was queued, so a retry days later
            // reproduces the first attempt rather than following a preference
            // the holder has since changed.
            String locale = message.getLocale() == null || message.getLocale().isBlank()
                    ? "fr" : message.getLocale();

            MimeMessage mime = mailSender.createMimeMessage();
            // true = multipart, so a plain-text alternative can accompany the
            // HTML. UTF-8 is not optional: Arabic subjects are otherwise
            // encoded as question marks.
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, true, StandardCharsets.UTF_8.name());

            helper.setFrom(props.email().from());
            helper.setTo(message.getRecipient());
//            helper.setSubject(template.subject(locale));
//            // (plainText, html) — the order matters; a client picks the last
//            // part it understands.
//            helper.setText(
//                    template.renderText(locale, message.getPayload()),
//                    template.renderHtml(locale, message.getPayload()));
            helper.setSubject(renderer.subject(template, locale, message.getPayload()));
            helper.setText(
                    renderer.text(template, locale, message.getPayload()),
                    renderer.html(template, locale, message.getPayload()));

            mailSender.send(mime);

            message.setStatus(EmailOutbox.Status.SENT);
            message.setSentAt(OffsetDateTime.now());
            message.setLastError(null);
            log.info("EMAIL_SENT id={} template={} locale={} to={}",
                    message.getId(), message.getTemplate(), locale, message.getRecipient());

        } catch (IllegalArgumentException e) {
            // Unknown template — a code/data mismatch that retrying cannot fix.
            message.setStatus(EmailOutbox.Status.FAILED);
            message.setLastError("Unknown template: " + message.getTemplate());
            log.error("EMAIL_TEMPLATE_UNKNOWN id={} template={}",
                    message.getId(), message.getTemplate());

        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            message.setLastError(reason);

            if (message.getAttempts() >= MAX_ATTEMPTS) {
                message.setStatus(EmailOutbox.Status.FAILED);
                log.error("EMAIL_FAILED_PERMANENTLY id={} to={} attempts={} reason={}",
                        message.getId(), message.getRecipient(), message.getAttempts(), reason);
            } else {
                log.warn("EMAIL_RETRY id={} to={} attempt={}/{} reason={}",
                        message.getId(), message.getRecipient(),
                        message.getAttempts(), MAX_ATTEMPTS, reason);
            }
        }
        outbox.save(message);
    }
}
