package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.card.CardStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Every status change, immutable. The audit trail is the product. */
@Entity
@Table(name = "institutional_card_status_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstitutionalCardStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "institutional_card_id", nullable = false)
    private Long institutionalCardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private CardStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    private CardStatus toStatus;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
