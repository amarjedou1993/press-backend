//package com.presscard.press_accreditation.card;
//
//import org.springframework.data.domain.Pageable;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//
//import java.util.List;
//
//public interface PrintRunRepository extends JpaRepository<PrintRun, Long> {
//
//    /** One actor's own history, newest first. */
//    List<PrintRun> findByPrintedByOrderByPrintedAtDesc(Long printedBy, Pageable pageable);
//
//    /** Everyone's, for the Ministry. */
//    List<PrintRun> findAllByOrderByPrintedAtDesc(Pageable pageable);
//
//    /**
//     * How many times each card has been produced.
//     *
//     * ⚠️ COUNTED FROM THE RUNS, not from cards.print_count.
//     *
//     * print_count is incremented when a PDF is generated. The printer never
//     * generates one — they take assets — so on the cards they produce that
//     * counter stays at zero for ever. Reading it would report every printed
//     * card as unprinted.
//     */
//    @Query("""
//           SELECT prc.cardId, COUNT(prc)
//           FROM PrintRunCard prc
//           WHERE prc.cardId IN :cardIds
//           GROUP BY prc.cardId
//           """)
//    List<Object[]> countByCardIds(@Param("cardIds") List<Long> cardIds);
//
//    /**
//     * Cards produced more than once — the Ministry's actual question.
//     *
//     * Not an alert and not a block: a misprint is normal and costs nothing.
//     * A card produced eleven times is worth asking about, and this is how it
//     * becomes visible rather than buried in a total.
//     */
//    @Query("""
//           SELECT prc.cardId, COUNT(prc) AS runs
//           FROM PrintRunCard prc
//           GROUP BY prc.cardId
//           HAVING COUNT(prc) >= :threshold
//           ORDER BY COUNT(prc) DESC
//           """)
//    List<Object[]> cardsProducedAtLeast(@Param("threshold") long threshold);
//
//    /** The cards in one run, for its detail view. */
//    @Query("SELECT prc.cardId FROM PrintRunCard prc WHERE prc.runId = :runId")
//    List<Long> cardIdsOfRun(@Param("runId") Long runId);
//
//    /**
//     * Has this honour card ever left the building?
//     *
//     * ⚠️ The question that decides whether its details may still be edited.
//     * Asked of the production history rather than a flag on the card, because
//     * the history is the fact and a flag would be a copy of it.
//     */
//    @Query("""
//           SELECT COUNT(prc) > 0 FROM PrintRunCard prc
//           WHERE prc.honourCardId = :honourCardId
//           """)
//    boolean honourCardWasProduced(@Param("honourCardId") Long honourCardId);
//}


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
     * How many times each of these cards has been produced.
     *
     * ⚠️ COUNTED FROM THE RUNS, not from cards.print_count.
     *
     * print_count is incremented when a PDF is generated. The printer never
     * generates one — they take assets — so on the cards they produce that
     * counter stays at zero for ever. Reading it would report every printed
     * card as unprinted.
     *
     * `IN` never matches a null, so honour rows are excluded here without
     * needing to be named.
     */
    @Query("""
           SELECT prc.cardId, COUNT(prc)
           FROM PrintRunCard prc
           WHERE prc.cardId IN :cardIds
           GROUP BY prc.cardId
           """)
    List<Object[]> countByCardIds(@Param("cardIds") List<Long> cardIds);

    /** The same question, for honour cards. */
    @Query("""
           SELECT prc.honourCardId, COUNT(prc)
           FROM PrintRunCard prc
           WHERE prc.honourCardId IN :honourCardIds
           GROUP BY prc.honourCardId
           """)
    List<Object[]> countByHonourCardIds(@Param("honourCardIds") List<Long> honourCardIds);

    /**
     * Cards produced more than once — the Ministry's actual question.
     *
     * Not an alert and not a block: a misprint is normal and costs nothing. A
     * card produced eleven times is worth asking about, and this is how it
     * becomes visible rather than buried in a total.
     *
     * ⚠️ WHERE cardId IS NOT NULL IS NOT OPTIONAL.
     *
     * Without it, every honour card groups together under a single null key —
     * and the statistics report one phantom card produced as many times as
     * there are honour cards. The screen would not fail; it would lie.
     *
     * That is what a newly nullable column does at a distance, in a query
     * written before it could be null.
     */
    @Query("""
           SELECT prc.cardId, COUNT(prc) AS runs
           FROM PrintRunCard prc
           WHERE prc.cardId IS NOT NULL
           GROUP BY prc.cardId
           HAVING COUNT(prc) >= :threshold
           ORDER BY COUNT(prc) DESC
           """)
    List<Object[]> cardsProducedAtLeast(@Param("threshold") long threshold);

    /**
     * Has this honour card ever left the building?
     *
     * ⚠️ The question that decides whether its details may still be edited.
     * Asked of the production history rather than a flag on the card, because
     * the history is the fact and a flag would be a copy of it — and copies
     * drift.
     */
    @Query("""
           SELECT COUNT(prc) > 0 FROM PrintRunCard prc
           WHERE prc.honourCardId = :honourCardId
           """)
    boolean honourCardWasProduced(@Param("honourCardId") Long honourCardId);

    /** The cards in one run, for its detail view. */
    @Query("SELECT prc.cardId FROM PrintRunCard prc WHERE prc.runId = :runId AND prc.cardId IS NOT NULL")
    List<Long> cardIdsOfRun(@Param("runId") Long runId);

    /** The honour cards in one run. */
    @Query("SELECT prc.honourCardId FROM PrintRunCard prc WHERE prc.runId = :runId AND prc.honourCardId IS NOT NULL")
    List<Long> honourCardIdsOfRun(@Param("runId") Long runId);
}