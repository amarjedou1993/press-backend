package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * One card inside one run.
 *
 * ⚠️ THE PER-CARD FACT, kept separately from the batch.
 *
 * The run is what a printer reads — "the 47 on 12 March". This is what an
 * administrator queries: "was Mr Fall's card in that batch?", and "how many
 * times has this card been produced?".
 */
@Entity
@Table(name = "print_run_cards")
@IdClass(PrintRunCard.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrintRunCard {

    @Id
    @Column(name = "run_id")
    private Long runId;

    @Id
    @Column(name = "card_id")
    private Long cardId;

    /** The composite key. Equals and hashCode are required by JPA. */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable {
        private Long runId;
        private Long cardId;

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key key)) return false;
            return Objects.equals(runId, key.runId)
                && Objects.equals(cardId, key.cardId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(runId, cardId);
        }
    }
}
