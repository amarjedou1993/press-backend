package com.presscard.press_accreditation.objection;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A ground on which a rejection may be contested.
 *
 * DATA, NOT CODE — HAPA can add, reword or retire a ground with an UPDATE
 * rather than a deployment. The same principle as document_requirements: the
 * regulator owns the rules, the system enforces them.
 */
@Entity
@Table(name = "objection_reasons")
@Getter
@NoArgsConstructor
public class ObjectionReason {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 200)
    private String labelFr;

    @Column(name = "label_ar", nullable = false, length = 200)
    private String labelAr;

    /** Shown beneath the label, so the candidate picks the right ground. */
    @Column(name = "hint_fr", columnDefinition = "text")
    private String hintFr;

    @Column(name = "hint_ar", columnDefinition = "text")
    private String hintAr;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;


    /** OTHER carries no guidance of its own — the argument does the work. */
    public boolean isFreeForm() {
        return "OTHER".equals(code);
    }
}
