package com.presscard.press_accreditation.card;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PrintRunRepository extends JpaRepository<PrintRun, Long> {

    /** One actor's own history, newest first. */
    List<PrintRun> findByPrintedByOrderByPrintedAtDesc(Long printedBy, Pageable pageable);

    /** Everyone's, for the Ministry. */
    List<PrintRun> findAllByOrderByPrintedAtDesc(Pageable pageable);

    /**
     * How many times each card has been produced.
     *
     * ⚠️ COUNTED FROM THE RUNS, not from cards.print_count.
     *
     * print_count is incremented when a PDF is generated. The printer never
     * generates one — they take assets — so on the cards they produce that
     * counter stays at zero for ever. Reading it would report every printed
     * card as unprinted.
     */
    @Query("""
           SELECT prc.cardId, COUNT(prc)
           FROM PrintRunCard prc
           WHERE prc.cardId IN :cardIds
           GROUP BY prc.cardId
           """)
    List<Object[]> countByCardIds(@Param("cardIds") List<Long> cardIds);

    /**
     * Cards produced more than once — the Ministry's actual question.
     *
     * Not an alert and not a block: a misprint is normal and costs nothing.
     * A card produced eleven times is worth asking about, and this is how it
     * becomes visible rather than buried in a total.
     */
    @Query("""
           SELECT prc.cardId, COUNT(prc) AS runs
           FROM PrintRunCard prc
           GROUP BY prc.cardId
           HAVING COUNT(prc) >= :threshold
           ORDER BY COUNT(prc) DESC
           """)
    List<Object[]> cardsProducedAtLeast(@Param("threshold") long threshold);

    /** The cards in one run, for its detail view. */
    @Query("SELECT prc.cardId FROM PrintRunCard prc WHERE prc.runId = :runId")
    List<Long> cardIdsOfRun(@Param("runId") Long runId);
}
