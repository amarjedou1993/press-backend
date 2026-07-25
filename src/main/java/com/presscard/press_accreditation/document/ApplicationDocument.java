package com.presscard.press_accreditation.document;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One piece of evidence attached to an application: either an uploaded FILE
 * (file_path set, url null) or a typed LINK (url set, file_path null) — the
 * database enforces that exclusivity via document_kind_consistency.
 *
 * needs_correction + observation are written by a reviewer in week 5 when a
 * correction round is opened; version increments when the candidate replaces
 * a flagged document.
 */
@Entity
@Table(name = "application_documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false)
    private Long applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocumentType docType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentType.Kind kind;

    /** Set for FILE documents; null for links. */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** Set for LINK documents; null for files. */
    @Column(length = 1000)
    private String url;

    /** Original filename, kept for display (the stored name is randomised). */
    @Transient
    private String displayName;

    @Column(name = "needs_correction", nullable = false)
    @Builder.Default
    private boolean needsCorrection = false;

    @Column(columnDefinition = "text")
    private String observation;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private OffsetDateTime uploadedAt;
}
