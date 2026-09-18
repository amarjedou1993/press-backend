package com.presscard.press_accreditation.institutional;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * A body that files its own staff for accreditation.
 *
 * ⚠️ A TABLE RATHER THAN A CONSTANT, and HAPA is simply its first row.
 *
 * An authority that later admits a national agency or a public broadcaster
 * should add a row, not cut a release. The cost of the general form is this
 * class; the cost of the particular one would have been a rewrite.
 */
@Entity
@Table(name = "institutions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Institution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name_fr", nullable = false, length = 200)
    private String nameFr;

    @Column(name = "name_ar", nullable = false, length = 200)
    private String nameAr;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * When this body was last told its cards are approaching expiry.
     *
     * ⚠️ NULL MEANS NEVER, and the job treats that as due. A body registered
     * after its cards were granted — a migration, a correction — should be
     * told, not skipped because nothing was recorded.
     */
    @Column(name = "renewal_notified_at")
    private OffsetDateTime renewalNotifiedAt;
}
