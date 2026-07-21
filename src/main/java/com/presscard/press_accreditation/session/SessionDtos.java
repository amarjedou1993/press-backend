package com.presscard.press_accreditation.session;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Session contracts. Creation takes a start date + FOUR per-phase day counts
 * (the "days per phase" model). The service derives the boundary dates and
 * the total; the client never computes calendar math it could get wrong.
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

    /** Admin view — full detail including the derived calendar and status. */
    public record SessionResponse(
            Long id,
            String type,
            String status,
            LocalDate startDate,
            int totalDays,
            LocalDate receivingEnd,
            LocalDate reviewEnd,
            LocalDate correctionEnd,
            LocalDate reclamationEnd,
            String nextPhase
    ) {
        static SessionResponse of(Session s) {
            return new SessionResponse(
                    s.getId(), s.getType().name(), s.getStatus().name(),
                    s.getStartDate(), s.getTotalDays(),
                    s.getReceivingEnd(), s.getReviewEnd(),
                    s.getCorrectionEnd(), s.getReclamationEnd(),
                    s.getStatus().next().map(Enum::name).orElse(null));
        }
    }

    /** Public view — only what a citizen needs; no internal fields. */
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
