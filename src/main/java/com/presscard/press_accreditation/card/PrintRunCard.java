//package com.presscard.press_accreditation.card;
//
//import jakarta.persistence.*;
//import lombok.*;
//
//import java.io.Serializable;
//import java.util.Objects;
//
///**
// * One card inside one run.
// *
// * ⚠️ THE PER-CARD FACT, kept separately from the batch.
// *
// * The run is what a printer reads — "the 47 on 12 March". This is what an
// * administrator queries: "was Mr Fall's card in that batch?", and "how many
// * times has this card been produced?".
// */
//@Entity
//@Table(name = "print_run_cards")
//@IdClass(PrintRunCard.Key.class)
//@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
//public class PrintRunCard {
//
//    @Id
//    @Column(name = "run_id")
//    private Long runId;
//
//    @Id
//    @Column(name = "card_id")
//    private Long cardId;
//
//    @Column(name = "honour_card_id")
//    private Long honourCardId;
//
//    /** The composite key. Equals and hashCode are required by JPA. */
//    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
//    public static class Key implements Serializable {
//        private Long runId;
//        private Long cardId;
//
//        @Override
//        public boolean equals(Object other) {
//            if (this == other) return true;
//            if (!(other instanceof Key key)) return false;
//            return Objects.equals(runId, key.runId)
//                && Objects.equals(cardId, key.cardId);
//        }
//
//        @Override
//        public int hashCode() {
//            return Objects.hash(runId, cardId);
//        }
//    }
//}


package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

/**
 * One card inside one run.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ A SURROGATE KEY, AND IT HAD TO BECOME ONE.
 *
 * This was (run_id, card_id) with both columns in a composite @IdClass. That
 * cannot survive an honour card: card_id is now nullable, and Hibernate
 * cannot hold a null in part of a key — every honour row would fail to
 * persist, and the failure would arrive at insert time with a message about
 * identifiers rather than about cards.
 *
 * So the identity moved to `id`, and the old pair became a uniqueness rule in
 * the migration instead: a card must not appear twice in one run, which is
 * what the composite key was actually enforcing.
 * ───────────────────────────────────────────────────────────────────────
 *
 * The run is what a printer reads — "the 47 on 12 March". This is what an
 * administrator queries: "was Mr Fall's card in that batch?", and "how many
 * times has this been produced?".
 */
@Entity
@Table(name = "print_run_cards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrintRunCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    /**
     * ⚠️ EXACTLY ONE OF THESE IS SET, and the database enforces it.
     *
     * A CHECK constraint refuses a row naming both or neither — see the
     * migration. Without it, "how many times has this been produced" would
     * count rows that name nothing.
     */
    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "honour_card_id")
    private Long honourCardId;
}