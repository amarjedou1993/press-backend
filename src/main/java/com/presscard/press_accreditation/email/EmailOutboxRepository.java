package com.presscard.press_accreditation.email;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {

    /**
     * The next batch to dispatch.
     *
     * ⚠️ FOR UPDATE SKIP LOCKED — this was described in the comment but not
     * in the query.
     *
     * Without it, two application instances running the worker at the same
     * moment select the SAME rows and send the same message twice. The
     * comment claimed protection the SQL did not provide, which is worse than
     * no comment: it stops anyone looking.
     *
     * SKIP LOCKED rather than a plain FOR UPDATE: the second instance takes a
     * different batch instead of waiting for the first, so throughput is
     * unaffected. This is the difference between a candidate receiving one
     * notification and receiving two — and a duplicate rejection notice is
     * not a small thing to receive.
     *
     * @Lock cannot be used here: Spring Data ignores it on native queries.
     * The clause has to be in the SQL.
     */
    @Query(value = """
           SELECT * FROM email_outbox
           WHERE status = 'PENDING' AND attempts < :maxAttempts
           ORDER BY created_at
           LIMIT :batchSize
           FOR UPDATE SKIP LOCKED
           """, nativeQuery = true)
    List<EmailOutbox> lockNextBatch(@Param("maxAttempts") int maxAttempts,
                                    @Param("batchSize") int batchSize);

    long countByStatus(EmailOutbox.Status status);

    /** Operational view: what has exhausted its retries and needs a human. */
    List<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailOutbox.Status status,
                                                       Pageable pageable);
}
