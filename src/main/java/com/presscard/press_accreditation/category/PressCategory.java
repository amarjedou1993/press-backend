package com.presscard.press_accreditation.category;

import jakarta.persistence.*;
import lombok.Getter;

/**
 * Read-only view of the seeded press_categories (V2 migration). Categories
 * are reference data — created by Flyway, never through the app in V1 — so
 * this entity is queried, never written.
 */
@Entity
@Table(name = "press_categories")
@Getter
public class PressCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(name = "label_fr", nullable = false)
    private String labelFr;

    @Column(name = "label_ar", nullable = false)
    private String labelAr;
}
