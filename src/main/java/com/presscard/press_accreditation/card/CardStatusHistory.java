package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One change in a card's life, immutable.
 *
 * proposedBy is set on a revocation: that act follows the chain which granted
 * the accreditation — proposed by the commission, executed by the super
 * admin — and the record must show both hands.
 */
@Entity
@Table(name = "card_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private CardStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private CardStatus toStatus;

    @Column(nullable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** The commission member who proposed a revocation. */
    @Column(name = "proposed_by")
    private Long proposedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
