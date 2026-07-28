package com.presscard.press_accreditation.email;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {

    /** Lookup is always by hash — the raw token exists only in the e-mail. */
    Optional<EmailToken> findByTokenHash(String tokenHash);

    /**
     * Invalidate a user's outstanding tokens of one type. Called before
     * issuing a new one, so "resend" cannot leave two live links — and so a
     * password reset the user did not request is neutralised the moment they
     * request one themselves.
     */
//    @Modifying
//    @Query("""
//           UPDATE EmailToken t SET t.usedAt = :now
//           WHERE t.userId = :userId AND t.type = :type AND t.usedAt IS NULL
//           """)
//    int invalidateOutstanding(@Param("userId") Long userId,
//                              @Param("type") EmailTokenType type,
//                              @Param("now") OffsetDateTime now);

    /**
     * Invalidate a user's outstanding tokens of one type.
     *
     * clearAutomatically + flushAutomatically are essential: this is a bulk
     * UPDATE that bypasses the persistence context, so without them a token
     * already loaded in this transaction would still read as unused — the
     * database correct, the in-memory copy stale.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE EmailToken t SET t.usedAt = :now
           WHERE t.userId = :userId AND t.type = :type AND t.usedAt IS NULL
           """)
    int invalidateOutstanding(@Param("userId") Long userId,
                              @Param("type") EmailTokenType type,
                              @Param("now") OffsetDateTime now);

    /** Rate-limit support: how many were issued recently. */
    @Query("""
           SELECT COUNT(t) FROM EmailToken t
           WHERE t.userId = :userId AND t.type = :type AND t.createdAt > :since
           """)
    long countRecent(@Param("userId") Long userId,
                     @Param("type") EmailTokenType type,
                     @Param("since") OffsetDateTime since);

    /** Housekeeping: expired and consumed tokens have no further use. */
    @Modifying
    @Query("DELETE FROM EmailToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
