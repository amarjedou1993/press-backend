package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * ⚠️ Long, not PrintRunCard.Key.
 *
 * The composite key is gone: card_id became nullable when honour cards
 * arrived, and Hibernate cannot hold a null in part of an identifier. The
 * table now has a surrogate `id`, and the old pair survives as a uniqueness
 * constraint in the migration — which is what it was actually enforcing.
 */
public interface PrintRunCardRepository extends JpaRepository<PrintRunCard, Long> {
    /**
     * Which series each of these runs produced.
     *
     * ⚠️ READ FROM THE ROWS, NOT FROM THE RUN.
     *
     * print_runs.kind records how a card left — assets or a signed PDF — and
     * deliberately not which series it was: the series is already on the card
     * the row points at, and a second copy would eventually disagree.
     *
     * So it is derived from WHICH COLUMN is set, which the one-provenance
     * CHECK makes unambiguous. One query for the whole page.
     */
    @Query("""
           SELECT prc.runId,
                  MIN(CASE
                      WHEN prc.cardId IS NOT NULL THEN 'CARD'
                      WHEN prc.honourCardId IS NOT NULL THEN 'HONOUR'
                      ELSE 'INSTITUTIONAL'
                  END)
           FROM PrintRunCard prc
           WHERE prc.runId IN :runIds
           GROUP BY prc.runId
           """)
    List<Object[]> seriesByRunIds(@Param("runIds") List<Long> runIds);
}