package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.card.Card;
import com.presscard.press_accreditation.card.CardRepository;
import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.error.SessionNotFoundException;
import com.presscard.press_accreditation.objection.ObjectionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * What a candidacy session produced, and what it still needs.
 *
 * ONE SERVICE FOR BOTH, deliberately. A running session and a closed one are
 * the same question asked at different moments — "where does this cohort
 * stand" — and splitting them would mean two implementations of the same
 * arithmetic, drifting apart until a closed session's figures disagreed with
 * the ones shown the day before it closed.
 *
 * THE OUTSTANDING WORK MATTERS AS MUCH AS THE RESULTS. An administrator
 * opening this screen mid-session should learn what needs pushing — dossiers
 * nobody has claimed, corrections nobody has answered, cards nobody has issued
 * — not merely what has happened. A report tells you the past; this has to be
 * usable as a tool.
 *
 * THE DRAFTS ARE COUNTED TOO. A session where sixty people started and forty
 * submitted has a story in that gap, and it is invisible if only submissions
 * are reported.
 */
@Service
public class SessionResultsService {

    /** Every figure the screen shows, in one round trip. */
    public record SessionResults(
            Long sessionId,
            String status,
            String statusLabelFr,
            boolean closed,
            LocalDate startDate,
            LocalDate receivingEnd,
            LocalDate reviewEnd,
            LocalDate correctionEnd,
            LocalDate reclamationEnd,
            LocalDate cardExpiryDate,

            /* ── the cohort ── */
            long started,          // drafts included — the gap tells a story
            long submitted,        // everything past DRAFT
            long inProgress,       // still with the commission or the candidate
            long accepted,         // ACCEPTED + CARD_ISSUED
            long rejected,         // REJECTED + FINAL_REJECTION
            long cardsIssued,

            /* ── the contested ones ── */
            long objectionsFiled,
            long objectionsUpheld,      // rejection overturned on reclamation
            long objectionsDismissed,   // FINAL_REJECTION

            /* ── what remains to be done ── */
            long unclaimed,             // nobody has taken these
            long awaitingCorrection,    // the candidate has not answered
            long acceptedWithoutCard,   // entitled, not yet issued
            long blockedFromCard,       // entitled but missing something

            /* ── by category, for HAPA's own reporting ── */
            List<CategoryTally> byCategory
    ) {}

    public record CategoryTally(
            String labelFr,
            long submitted,
            long accepted,
            long rejected,
            long cardsIssued
    ) {}

    /** One candidate's passage through the session. */
    public record CandidateOutcome(
            Long applicationId,
            String fullName,
            String categoryLabelFr,
            String specialisationFr,
            String institution,
            String status,
            String statusLabelFr,
            /** Grouped outcome: PENDING | ACCEPTED | REJECTED | DRAFT. */
            String outcome,
            OffsetDateTime submittedAt,
            boolean objected,
            String cardNumber,
            String cardStatus
    ) {}

    private final SessionRepository sessionRepository;
    private final ApplicationRepository applicationRepository;
    private final CardRepository cardRepository;
    private final PressCategoryRepository categoryRepository;
    private final ObjectionRepository objectionRepository;
    private final UserRepository userRepository;
    private final CandidateProfileLookup profileLookup;

    public SessionResultsService(SessionRepository sessionRepository,
                                 ApplicationRepository applicationRepository,
                                 CardRepository cardRepository,
                                 PressCategoryRepository categoryRepository,
                                 ObjectionRepository objectionRepository,
                                 UserRepository userRepository,
                                 CandidateProfileLookup profileLookup) {
        this.sessionRepository = sessionRepository;
        this.applicationRepository = applicationRepository;
        this.cardRepository = cardRepository;
        this.categoryRepository = categoryRepository;
        this.objectionRepository = objectionRepository;
        this.userRepository = userRepository;
        this.profileLookup = profileLookup;
    }

    /* ══ the figures ══════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public SessionResults results(Long sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        List<Application> all = applicationRepository.findBySessionId(sessionId);

        Map<ApplicationStatus, Long> byStatus = new EnumMap<>(ApplicationStatus.class);
        for (Application a : all) {
            byStatus.merge(a.getStatus(), 1L, Long::sum);
        }

        long started = all.size();
        long drafts = byStatus.getOrDefault(ApplicationStatus.DRAFT, 0L);
        long submitted = started - drafts;

        long accepted = count(byStatus, ApplicationStatus.ACCEPTED)
                      + count(byStatus, ApplicationStatus.CARD_ISSUED);
        long rejected = count(byStatus, ApplicationStatus.REJECTED)
                      + count(byStatus, ApplicationStatus.FINAL_REJECTION);
        long inProgress = count(byStatus, ApplicationStatus.UNDER_REVIEW)
                        + count(byStatus, ApplicationStatus.UNDER_FINAL_REVIEW)
                        + count(byStatus, ApplicationStatus.UNDER_RECLAMATION)
                        + count(byStatus, ApplicationStatus.CORRECTION_REQUESTED);
        long cardsIssued = count(byStatus, ApplicationStatus.CARD_ISSUED);

        /* ── the contested ones ──
           An objection that succeeded means the commission's first decision
           was overturned. HAPA should be able to see that rate: it is the
           closest thing the system has to a measure of its own review
           quality. */
        List<Long> applicationIds = all.stream().map(Application::getId).toList();
        long objectionsFiled = applicationIds.isEmpty() ? 0
                : objectionRepository.countByApplicationIdIn(applicationIds);
        long objectionsDismissed = count(byStatus, ApplicationStatus.FINAL_REJECTION);
        // Upheld = objected AND now accepted. A dossier reaching ACCEPTED after
        // an objection was overturned on reclamation.
        long objectionsUpheld = all.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.ACCEPTED
                          || a.getStatus() == ApplicationStatus.CARD_ISSUED)
                .filter(a -> objectionRepository.existsByApplicationId(a.getId()))
                .count();

        /* ── what remains ── */
        long unclaimed = all.stream()
                .filter(a -> a.getClaimedBy() == null)
                .filter(a -> a.getStatus() == ApplicationStatus.UNDER_REVIEW
                          || a.getStatus() == ApplicationStatus.UNDER_FINAL_REVIEW
                          || a.getStatus() == ApplicationStatus.UNDER_RECLAMATION)
                .count();
        long awaitingCorrection = count(byStatus, ApplicationStatus.CORRECTION_REQUESTED);
        long acceptedWithoutCard = count(byStatus, ApplicationStatus.ACCEPTED);

        // Entitled to a card but unable to produce one — a missing photograph,
        // specialisation or institution. Surfaced here because it is a
        // BLOCKING problem discovered too late if it waits for issuance day.
        long blockedFromCard = all.stream()
                .filter(a -> a.getStatus() == ApplicationStatus.ACCEPTED)
                .filter(this::cannotProduceCard)
                .count();

        return new SessionResults(
                session.getId(),
                session.getStatus().name(),
                session.getStatus().labelFr(),
                session.getStatus() == SessionStatus.CLOSED,
                session.getStartDate(),
                session.getReceivingEnd(),
                session.getReviewEnd(),
                session.getCorrectionEnd(),
                session.getReclamationEnd(),
                session.getCardExpiryDate(),

                started, submitted, inProgress, accepted, rejected, cardsIssued,
                objectionsFiled, objectionsUpheld, objectionsDismissed,
                unclaimed, awaitingCorrection, acceptedWithoutCard, blockedFromCard,

                tallyByCategory(all));
    }

    /* ══ the cohort, one by one ═══════════════════════════════ */

    @Transactional(readOnly = true)
    public List<CandidateOutcome> candidates(Long sessionId) {
        List<Application> all = applicationRepository.findBySessionId(sessionId);

        return all.stream()
                .sorted(Comparator.comparing(
                        Application::getSubmittedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toOutcome)
                .toList();
    }

    private CandidateOutcome toOutcome(Application application) {
        User candidate = userRepository.findById(application.getCandidateId())
                .orElse(null);
        String category = categoryRepository.findById(application.getCategoryId())
                .map(PressCategory::getLabelFr).orElse("—");
        Card card = cardRepository.findByApplicationId(application.getId()).orElse(null);

        boolean lapsed = card != null && card.isExpired()
                && card.getStatus() == CardStatus.VALID;

        return new CandidateOutcome(
                application.getId(),
                candidate == null ? "—" : candidate.getFullName(),
                category,
                profileLookup.specialisationLabelOf(application),
                application.getInstitution(),
                application.getStatus().name(),
                application.getStatus().labelFr(),
                outcomeOf(application.getStatus()),
                application.getSubmittedAt(),
                objectionRepository.existsByApplicationId(application.getId()),
                card == null ? null : card.getCardNumber(),
                card == null ? null : (lapsed ? "EXPIRED" : card.getStatus().name()));
    }

    /**
     * The four buckets a candidate's dossier can be in, from their point of
     * view — which is coarser than the nine-state machine and is what a filter
     * should offer. "UNDER_FINAL_REVIEW" is a distinction the commission
     * cares about; "still being examined" is what anyone else needs.
     */
    private String outcomeOf(ApplicationStatus status) {
        return switch (status) {
            case DRAFT -> "DRAFT";
            case ACCEPTED, CARD_ISSUED -> "ACCEPTED";
            case REJECTED, FINAL_REJECTION -> "REJECTED";
            default -> "PENDING";
        };
    }

    private List<CategoryTally> tallyByCategory(List<Application> all) {
        Map<Long, List<Application>> grouped = new LinkedHashMap<>();
        for (Application a : all) {
            if (a.getStatus() == ApplicationStatus.DRAFT) continue;
            grouped.computeIfAbsent(a.getCategoryId(), k -> new ArrayList<>()).add(a);
        }

        return grouped.entrySet().stream()
                .map(entry -> {
                    List<Application> group = entry.getValue();
                    return new CategoryTally(
                            categoryRepository.findById(entry.getKey())
                                    .map(PressCategory::getLabelFr).orElse("—"),
                            group.size(),
                            group.stream().filter(a ->
                                    a.getStatus() == ApplicationStatus.ACCEPTED
                                 || a.getStatus() == ApplicationStatus.CARD_ISSUED).count(),
                            group.stream().filter(a ->
                                    a.getStatus() == ApplicationStatus.REJECTED
                                 || a.getStatus() == ApplicationStatus.FINAL_REJECTION).count(),
                            group.stream().filter(a ->
                                    a.getStatus() == ApplicationStatus.CARD_ISSUED).count());
                })
                .sorted(Comparator.comparingLong(CategoryTally::submitted).reversed())
                .toList();
    }

    private boolean cannotProduceCard(Application application) {
        return application.getSpecialisationId() == null
                || application.getInstitution() == null
                || application.getInstitution().isBlank()
                || !profileLookup.hasPhoto(application.getCandidateId());
    }

    private long count(Map<ApplicationStatus, Long> map, ApplicationStatus status) {
        return map.getOrDefault(status, 0L);
    }
}
