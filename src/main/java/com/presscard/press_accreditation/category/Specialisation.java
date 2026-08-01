package com.presscard.press_accreditation.category;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What the holder does — printed on the card as التخصص.
 *
 * A CLOSED LIST, like press categories and objection reasons: every sample
 * card reads صحفي, which is a controlled vocabulary rather than free text, and
 * a card's wording should not vary with whatever a candidate typed.
 *
 * Data, not code — HAPA adds or retires one with an UPDATE.
 */
@Entity
@Table(name = "specialisations")
@Getter
@NoArgsConstructor
public class Specialisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "label_fr", nullable = false, length = 120)
    private String labelFr;

    @Column(name = "label_ar", nullable = false, length = 120)
    private String labelAr;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private boolean active;
}
