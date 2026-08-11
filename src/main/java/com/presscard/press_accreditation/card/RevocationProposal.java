package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A commission member's proposal that a card be withdrawn.
 *
 * TWO HANDS, deliberately. The commission proposes; the Authority executes.
 * That mirrors how the card was granted — the commission decided entitlement,
 * the Authority issued — and it is what makes a withdrawal defensible if
 * challenged. A super admin acting alone can be characterised as an
 * administrative act against a journalist; a commission proposal executed by
 * the Authority cannot.
 *
 * ONE PENDING PROPOSAL PER CARD, enforced by a partial unique index: two
 * members proposing on different grounds would otherwise leave a live proposal
 * against an already-revoked card.
 */
@Entity
@Table(name = "revocation_proposals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevocationProposal {

    public enum Status {
        /** Awaiting the Authority's decision. */
        PENDING("En attente de décision"),
        /** The card was withdrawn. */
        EXECUTED("Exécutée"),
        /** The Authority refused, with a reason. */
        DECLINED("Refusée"),
        /** The proposer withdrew it before a decision. */
        WITHDRAWN("Retirée par son auteur");

        private final String labelFr;

        Status(String labelFr) { this.labelFr = labelFr; }

        public String labelFr() { return labelFr; }

        public boolean isOpen() { return this == PENDING; }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "ground_id", nullable = false)
    private Long groundId;

    /** What the proposer alleges, in their own words. */
    @Column(nullable = false, columnDefinition = "text")
    private String statement;

    @Column(name = "proposed_by", nullable = false)
    private Long proposedBy;

    @Column(name = "proposed_at", insertable = false, updatable = false)
    private OffsetDateTime proposedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    /** Required on DECLINED: a refusal nobody can read is one they repeat. */
    @Column(name = "decided_note", columnDefinition = "text")
    private String decidedNote;
}
