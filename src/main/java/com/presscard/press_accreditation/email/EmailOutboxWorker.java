package com.presscard.press_accreditation.email;

import com.presscard.press_accreditation.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Drains the outbox: picks up PENDING rows and hands them to SMTP.
 *
 * Retry policy — attempts are counted, and a row that exhausts MAX_ATTEMPTS
 * is marked FAILED rather than retried forever. A permanently bad address
 * would otherwise be tried every ten seconds until the end of time, burying
 * the transient failures that actually deserve attention.
 *
 * FAILED rows are a QUEUE FOR A HUMAN, not a dead end: /actuator/health
 * surfaces the count, and an administrator can correct the address and requeue.
 *
 * Disabled when app.email.enabled is false, so tests and offline development
 * never touch a mail server.
 */
@Component
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
public class EmailOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger("EMAIL_WORKER");

    private static final int MAX_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 20;

    private final EmailOutboxRepository outbox;
    private final JavaMailSender mailSender;
    private final AppProperties props;

    public EmailOutboxWorker(EmailOutboxRepository outbox,
                             JavaMailSender mailSender,
                             AppProperties props) {
        this.outbox = outbox;
        this.mailSender = mailSender;
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

            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(props.email().from());
            mail.setTo(message.getRecipient());
            mail.setSubject(template.subject());
            mail.setText(template.render(message.getPayload()));

            mailSender.send(mail);

            message.setStatus(EmailOutbox.Status.SENT);
            message.setSentAt(OffsetDateTime.now());
            message.setLastError(null);
            log.info("EMAIL_SENT id={} template={} to={}",
                    message.getId(), message.getTemplate(), message.getRecipient());

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
