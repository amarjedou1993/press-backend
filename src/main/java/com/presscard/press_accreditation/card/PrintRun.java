package com.presscard.press_accreditation.card;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** One production run: a batch of cards leaving the building. */
@Entity
@Table(name = "print_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PrintRun {

    /**
     * What left.
     *
     * ⚠️ NOT INTERCHANGEABLE. The printer receives ASSETS — photograph,
     * verification QR, reference preview. The signed card PDF never reaches
     * them, so the Ministry's layout and signature stay inside.
     *
     * A history that conflated the two would record the printer as having
     * held the signed document.
     */
    public enum Kind { ASSETS, PDF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "printed_by", nullable = false)
    private Long printedBy;

    @Column(name = "printed_at", insertable = false, updatable = false)
    private OffsetDateTime printedAt;

    /** Null when a run spans sessions — an administrator's batch can. */
    @Column(name = "session_id")
    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** Only meaningful for a PDF run. */
    @Column(length = 20)
    private String layout;

    @Column(name = "card_count", nullable = false)
    private int cardCount;
}
