//package com.presscard.press_accreditation.card;
//
//import org.springframework.data.jpa.repository.JpaRepository;
//
//public interface PrintRunCardRepository
//        extends JpaRepository<PrintRunCard, PrintRunCard.Key> {
//}

package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ⚠️ Long, not PrintRunCard.Key.
 *
 * The composite key is gone: card_id became nullable when honour cards
 * arrived, and Hibernate cannot hold a null in part of an identifier. The
 * table now has a surrogate `id`, and the old pair survives as a uniqueness
 * constraint in the migration — which is what it was actually enforcing.
 */
public interface PrintRunCardRepository extends JpaRepository<PrintRunCard, Long> {
}