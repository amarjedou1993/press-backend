package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A ground on which a card may be withdrawn.
 *
 * A CLOSED LIST, like objection reasons: it tells the Authority what is being
 * alleged before they read a word, and it lets HAPA report on why cards are
 * withdrawn across a cycle. Data, not code — a new ground is an UPDATE.
 */
@Entity
@Table(name = "revocation_grounds")
@Getter
@NoArgsConstructor
public class RevocationGround {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 200)
    private String labelFr;

    @Column(name = "label_ar", nullable = false, length = 200)
    private String labelAr;

    @Column(name = "hint_fr", columnDefinition = "text")
    private String hintFr;

    /**
     * Whether the allegation is serious enough that the card should be
     * suspended while the proposal is examined.
     *
     * A forged dossier or a card used for something other than journalism
     * should not stay in force for the days a decision takes — but a holder
     * who has simply stopped working, or has died, needs no precaution.
     */
    @Column(name = "warrants_immediate_suspension", nullable = false)
    private boolean warrantsImmediateSuspension;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;

    /** These are administrative withdrawals, not disciplinary ones. */
    public boolean isAdministrative() {
        return "DECEASED".equals(code) || "HOLDER_REQUEST".equals(code);
    }
}
