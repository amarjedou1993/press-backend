package com.presscard.press_accreditation.session;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class SessionDtos {

    private SessionDtos() {}

    public record CreateSessionRequest(
            @NotNull @Future LocalDate startDate,
            @NotNull @Min(1) Integer receivingDays,
            @NotNull @Min(1) Integer reviewDays,
            @NotNull @Min(1) Integer correctionDays,
            @NotNull @Min(1) Integer reclamationDays,
            @NotNull(message = "Indiquez la date d'expiration des cartes.")
            @Future(message = "La date d'expiration doit être future.")
            LocalDate cardExpiryDate,
            /**
             * What kind of cycle this is.
             *
             * ⚠️ NOT OPTIONAL, AND NOT DEFAULTED TO CANDIDACY.
             *
             * A default would let a renewal session be created as a candidacy by
             * omission — and the two differ in what they ask for, who may file,
             * and whether two hundred invitations go out. That is not a mistake to
             * make silently.
             */
            @NotNull(message = "Le type de session est requis.")
            SessionType type
    ) {
        public int totalDays() {
            return receivingDays + reviewDays + correctionDays + reclamationDays;
        }
    }

    public record SessionResponse(
            Long id,
            String type,
            String status,
            /** The phase as HAPA names it — the label now travels with the code. */
            String statusLabelFr,
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
            String nextPhase,
            LocalDate cardExpiryDate,
            /**
             * Dossiers still awaiting their candidate's corrections.
             *
             * Carried on the SESSION so the phase-advance confirmation has it
             * at the moment of asking. Leaving the correction phase rejects
             * every one of them automatically — a decision that ends
             * accreditations, taken by someone who believes they are advancing
             * a calendar. The warning does not change the outcome; it changes
             * whether the administrator chose it.
             *
             * Zero outside the CORRECTION phase: nothing is awaiting an answer
             * then, and counting would be a query per session to learn it.
             */
            long awaitingCorrection
    ) {

        /**
         * Without the correction count.
         *
         * For every response where the figure is irrelevant — a creation, a
         * phase transition, the public list. Zero rather than null: no dossier
         * awaits an answer unless someone has counted and found one.
         */
        static SessionResponse of(Session s) {
            return of(s, 0L);
        }

        /**
         * With the count.
         *
         * A SECOND OVERLOAD rather than a repository inside the record: a
         * static factory cannot query, and giving a DTO a dependency so it
         * could would turn a mapping into a service. The caller counts,
         * because the caller is what knows whether the figure is worth a
         * query.
         */
        static SessionResponse of(Session s, long awaitingCorrection) {
            LocalDate phaseEnd = s.currentPhaseEnd();
            Integer allotted = phaseEnd == null ? null : s.allottedDaysFor(s.getStatus());
            Integer remaining = phaseEnd == null
                    ? null
                    : (int) ChronoUnit.DAYS.between(LocalDate.now(), phaseEnd);

            return new SessionResponse(
                    s.getId(), s.getType().name(), s.getStatus().name(),
                    s.getStatus().labelFr(),
                    s.getStartDate(), s.getTotalDays(),
                    s.getReceivingDays(), s.getReviewDays(),
                    s.getCorrectionDays(), s.getReclamationDays(),
                    s.getReceivingEnd(), s.getReviewEnd(),
                    s.getCorrectionEnd(), s.getReclamationEnd(),
                    s.getPhaseStartedAt(), phaseEnd, allotted, remaining,
                    s.getStatus().next().map(Enum::name).orElse(null),
                    s.getCardExpiryDate(),
                    awaitingCorrection
            );
        }
    }

    /**
     * What the creation form needs to stop an admin choosing a date that
     * will be refused. Better to grey out the impossible dates than to
     * explain the refusal afterwards.
     */
    public record SessionSchedulingRules(
            int minimumGapDays,
            LocalDate lastSessionStart,     // null if none exists yet
            LocalDate earliestNextStart     // never before tomorrow
    ) {}

    /**
     * Public view — only what a citizen needs.
     *
     * Deliberately three fields. A member of the public deciding whether to
     * apply needs to know that a session is open and when it closes; the
     * internal phase calendar, the correction counts and the card expiry are
     * the Authority's business.
     */
//    public record PublicSessionResponse(
//            Long id,
//            LocalDate startDate,
//            LocalDate receivingEnd
//    ) {
//        static PublicSessionResponse of(Session s) {
//            return new PublicSessionResponse(
//                    s.getId(), s.getStartDate(), s.getReceivingEnd());
//        }
//    }

    /**
     * What the public page may see of a session.
     *
     * ⚠️ `type` IS PART OF THE PUBLIC VIEW, and it has to be.
     *
     * PublicSessionController returns every RECEIVING session, of either
     * kind — which is right: a holder whose invitation went to spam needs
     * somewhere to learn their window is open.
     *
     * But without this field the page cannot tell them apart. A renewal
     * would render exactly like a candidature, with a button inviting a
     * stranger to file a dossier they may not file, and a holder to create
     * a second account under which their card does not exist.
     *
     * It discloses nothing: that a renewal cycle is running is precisely
     * what the page exists to announce.
     */
    public record PublicSessionResponse(
            Long id,
            SessionType type,
            LocalDate startDate,
            LocalDate receivingEnd
    ) {
        public static PublicSessionResponse of(Session s) {
            return new PublicSessionResponse(
                    s.getId(), s.getType(), s.getStartDate(), s.getReceivingEnd());
        }
    }
}
