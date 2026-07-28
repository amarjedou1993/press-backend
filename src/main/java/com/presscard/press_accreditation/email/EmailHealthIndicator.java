package com.presscard.press_accreditation.email;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the outbox at /actuator/health.
 *
 * A growing PENDING count means SMTP is unreachable; a non-zero FAILED count
 * means someone has NOT received a notification they were entitled to — a
 * decision, a correction request, a deadline. Both deserve an alert rather
 * than a log line nobody reads.
 */
@Component("emailOutbox")
public class EmailHealthIndicator implements HealthIndicator {

    /** Above this, delivery is not merely slow — something is wrong. */
    private static final long PENDING_ALERT_THRESHOLD = 50;

    private final EmailOutboxRepository outbox;

    public EmailHealthIndicator(EmailOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public Health health() {
        long pending = outbox.countByStatus(EmailOutbox.Status.PENDING);
        long failed = outbox.countByStatus(EmailOutbox.Status.FAILED);

        Health.Builder builder = (failed > 0 || pending > PENDING_ALERT_THRESHOLD)
                ? Health.status("DEGRADED")
                : Health.up();

        return builder
                .withDetail("pending", pending)
                .withDetail("failed", failed)
                .withDetail("note", failed > 0
                        ? "Des notifications n'ont pas pu être délivrées — vérifier les adresses."
                        : "OK")
                .build();
    }
}
