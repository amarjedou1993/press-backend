package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
