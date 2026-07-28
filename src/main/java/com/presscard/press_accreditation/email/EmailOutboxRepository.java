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
     * PESSIMISTIC_WRITE with SKIP LOCKED: if two application instances ever
     * run the worker simultaneously, each takes a different batch instead of
     * both sending the same mail. Cheap insurance, and the difference between
     * a candidate receiving one notification and receiving two.
     */
//    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
           SELECT * FROM email_outbox
           WHERE status = 'PENDING' AND attempts < :maxAttempts
           ORDER BY created_at
           LIMIT :batchSize
           """, nativeQuery = true)
    List<EmailOutbox> lockNextBatch(@Param("maxAttempts") int maxAttempts,
                                    @Param("batchSize") int batchSize);

    long countByStatus(EmailOutbox.Status status);

    /** Operational view: what has exhausted its retries and needs a human. */
    List<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailOutbox.Status status, Pageable pageable);
}
