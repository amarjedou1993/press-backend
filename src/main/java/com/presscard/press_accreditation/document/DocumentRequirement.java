package com.presscard.press_accreditation.document;

import jakarta.persistence.*;
import lombok.Getter;

/**
 * A rule from the seeded document_requirements table (V2__seed_catalog.sql).
 * Read-only reference data — HAPA changes what a category requires by editing
 * rows here, never by editing code.
 *
 * The semantics of alternativeGroup are the whole point:
 *  · NULL           → this document is MANDATORY on its own
 *  · a group number → this document is one OPTION among several; satisfying
 *                     ANY ONE requirement in the group satisfies the group
 *
 * So "international media" has two NULL-group rows (contract AND 3 links,
 * both required), while "freelancer" has three rows in group 1 (certificate
 * OR website OR 3 links).
 */
@Entity
@Table(name = "document_requirements")
@Getter
public class DocumentRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocumentType docType;

    @Column(name = "min_count", nullable = false)
    private int minCount;

    /** null = mandatory; same non-null value = alternatives to one another. */
    @Column(name = "alternative_group")
    private Integer alternativeGroup;

    public boolean isMandatory() {
        return alternativeGroup == null;
    }
}
