package com.presscard.press_accreditation.session;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Session contracts. Creation takes a start date + four per-phase day counts.
 * The response exposes BOTH the allotted durations and the current forecast
 * boundaries, plus what the frontend needs to show an honest countdown:
 * phaseStartedAt, currentPhaseEnd and daysRemainingInPhase.
 */
public final class SessionDtos {

    private SessionDtos() {}

    public record CreateSessionRequest(
            @NotNull @Future LocalDate startDate,
            @NotNull @Min(1) Integer receivingDays,
            @NotNull @Min(1) Integer reviewDays,
            @NotNull @Min(1) Integer correctionDays,
            @NotNull @Min(1) Integer reclamationDays
    ) {
        public int totalDays() {
            return receivingDays + reviewDays + correctionDays + reclamationDays;
        }
    }

    public record SessionResponse(
            Long id,
            String type,
            String status,
            LocalDate startDate,
            int totalDays,
            // allotted durations (the guarantee)
            int receivingDays,
            int reviewDays,
            int correctionDays,
            int reclamationDays,
            // current forecast
            LocalDate receivingEnd,
            LocalDate reviewEnd,
            LocalDate correctionEnd,
            LocalDate reclamationEnd,
            // countdown support
            LocalDate phaseStartedAt,
            LocalDate currentPhaseEnd,
            Integer allottedDaysInPhase,
            Integer daysRemainingInPhase,   // negative = overdue; null outside active phases
            String nextPhase
    ) {
        static SessionResponse of(Session s) {
            LocalDate phaseEnd = s.currentPhaseEnd();
            Integer allotted = phaseEnd == null ? null : s.allottedDaysFor(s.getStatus());
            Integer remaining = phaseEnd == null
                    ? null
                    : (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), phaseEnd);

            return new SessionResponse(
                    s.getId(), s.getType().name(), s.getStatus().name(),
                    s.getStartDate(), s.getTotalDays(),
                    s.getReceivingDays(), s.getReviewDays(),
                    s.getCorrectionDays(), s.getReclamationDays(),
                    s.getReceivingEnd(), s.getReviewEnd(),
                    s.getCorrectionEnd(), s.getReclamationEnd(),
                    s.getPhaseStartedAt(), phaseEnd, allotted, remaining,
                    s.getStatus().next().map(Enum::name).orElse(null));
        }
    }

    /** Public view — only what a citizen needs. */
    public record PublicSessionResponse(
            Long id,
            LocalDate startDate,
            LocalDate receivingEnd
    ) {
        static PublicSessionResponse of(Session s) {
            return new PublicSessionResponse(
                    s.getId(), s.getStartDate(), s.getReceivingEnd());
        }
    }
}
