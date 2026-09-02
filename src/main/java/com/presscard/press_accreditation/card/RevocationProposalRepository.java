package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RevocationProposalRepository
        extends JpaRepository<RevocationProposal, Long> {

    /** The open proposal against a card, if any — at most one by constraint. */
    Optional<RevocationProposal> findByCardIdAndStatus(
            Long cardId, RevocationProposal.Status status);

    boolean existsByCardIdAndStatus(Long cardId, RevocationProposal.Status status);

    /** Everything proposed against a card, newest first — the card's history. */
    List<RevocationProposal> findByCardIdOrderByProposedAtDesc(Long cardId);

    /** The Authority's queue. */
    List<RevocationProposal> findByStatusOrderByProposedAtAsc(
            RevocationProposal.Status status);

    /** A commission member's own proposals. */
    List<RevocationProposal> findByProposedByOrderByProposedAtDesc(Long proposerId);

    @Query("SELECT COUNT(p) FROM RevocationProposal p WHERE p.status = 'PENDING'")
    long countPending();

    /**
     * Pending proposals against any of these cards.
     *
     * ⚠️ ONE QUERY replacing one findByCardIdAndStatus per row.
     *
     * The commission's register loads every issued card, and each row asked
     * whether a withdrawal was pending against it — so two hundred cards cost
     * two hundred lookups on top of the three the mapping already made.
     *
     * The database guarantees at most one PENDING proposal per card, so the
     * result maps cleanly by cardId with no collision.
     */
    @Query("""
           SELECT p FROM RevocationProposal p
           WHERE p.cardId IN :cardIds AND p.status = :status
           """)
    List<RevocationProposal> findByCardIdInAndStatus(
            @Param("cardIds") List<Long> cardIds,
            @Param("status") RevocationProposal.Status status);
}
