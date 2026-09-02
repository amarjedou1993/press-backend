package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Issued cards, as the commission needs to see them.
 *
 * WHY A REVIEWER SEES THIS AT ALL: a member cannot propose a withdrawal
 * against a card they cannot find. The proposal mechanism existed with no way
 * to reach it — a feature built and unreachable, which looks complete in a
 * review and fails in use.
 *
 * WHAT IT DISCLOSES, AND WHAT IT DOES NOT. The commission decided these
 * accreditations, so the holder's name, category, institution and card status
 * are properly theirs to see. The VERIFICATION TOKEN is not included: it is
 * the key that resolves to a holder's photograph, and a list carrying it would
 * turn any leak into a lookup table. Nor is the NNI — the commission examined
 * it during the review; it has no bearing on whether a card should be
 * withdrawn.
 *
 * READ-ONLY. Nothing here changes a card: a reviewer proposes through
 * ReviewerRevocationController, and only the Authority acts.
 */
@RestController
@RequestMapping("/api/reviewer/cards")
@PreAuthorize("hasRole('REVIEWER')")
public class ReviewerCardController {

    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    /** A card, as the commission reads it. */
    public record ReviewerCardResponse(
            Long cardId,
            String cardNumber,
            String holderFullName,
            String categoryLabelFr,
            String specialisationFr,
            String institution,
            LocalDate issuedAt,
            LocalDate expiresAt,
            String status,
            String statusLabelFr,
            boolean expired,
            /** True while a proposal against this card awaits a decision. */
            boolean proposalPending,
            /** Set when the pending proposal is this reviewer's own. */
            boolean proposedByMe,
            /**
             * Null when a proposal may be filed; otherwise the reason it may
             * not — decided HERE so the UI never has to work it out, and the
             * button and its explanation cannot disagree.
             */
            String cannotProposeReasonFr,
            /**
             * The session that produced this card.
             *
             * ⚠️ Cards are issued in COHORTS — everyone accredited in one
             * session shares an expiry, and the decisions behind them were
             * taken in one sitting. Reading back over a session is a real
             * task for a member; reading over "every card ever" is not.
             */
            Long sessionId,
            /** "Session du 12 mars 2026" — composed here, once. */
            String sessionLabel
    ) {}

    private final CardRepository cardRepository;
    private final RevocationProposalRepository proposalRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public ReviewerCardController(CardRepository cardRepository,
                                  RevocationProposalRepository proposalRepository,
                                  ApplicationRepository applicationRepository,
                                  PressCategoryRepository categoryRepository,
                                  SessionRepository sessionRepository,
                                  UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.proposalRepository = proposalRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Every issued card, newest first.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ FIVE QUERIES, WHATEVER THE REGISTER'S SIZE.
     *
     * It was FOUR PER CARD — application, holder, category, and a pending-
     * proposal check. This register loads every card ever issued, so two
     * hundred cards meant eight hundred sequential round trips holding one
     * connection from a pool of ten.
     *
     * The proposal lookup was the one that made this the worst of the three
     * card listings: the other two do not ask it.
     * ───────────────────────────────────────────────────────────────────
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<ReviewerCardResponse> cards(java.security.Principal principal) {
        Long me = userRepository.findByEmail(principal.getName()).orElseThrow().getId();

        List<Card> cards = cardRepository.findAllByOrderByIssuedAtDesc();
        if (cards.isEmpty()) {
            return List.of();
        }

        List<Long> applicationIds = cards.stream()
                .map(Card::getApplicationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Application> applications = applicationRepository
                .findAllById(applicationIds).stream()
                .collect(Collectors.toMap(Application::getId, Function.identity()));

        List<Long> candidateIds = applications.values().stream()
                .map(Application::getCandidateId).distinct().toList();

        Map<Long, User> holders = userRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // Both catalogues are a handful of rows that cannot change mid-request.
        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        Map<Long, Session> sessions = sessionRepository.findAll().stream()
                .collect(Collectors.toMap(Session::getId, Function.identity()));

        /*
         * ⚠️ The database allows at most one PENDING proposal per card, so
         * this maps cleanly by cardId. If that constraint were ever relaxed,
         * toMap would throw on the duplicate — loudly, which is the right
         * failure for a rule that decides whether someone keeps a card.
         */
        Map<Long, RevocationProposal> pending = proposalRepository
                .findByCardIdInAndStatus(
                        cards.stream().map(Card::getId).toList(),
                        RevocationProposal.Status.PENDING).stream()
                .collect(Collectors.toMap(
                        RevocationProposal::getCardId, Function.identity()));

        return cards.stream()
                .map(card -> toResponse(card, me, applications, holders,
                        categories, sessions, pending))
                .toList();
    }

    /**
     * ⚠️ NO REPOSITORY CALLS, AND NONE MAY BE ADDED.
     *
     * A lookup placed here is a lookup per card, and its cost will not show in
     * a code review — it will show as a register that takes seconds to open,
     * for reasons nobody can point at. Anything new is batched above.
     */
    private ReviewerCardResponse toResponse(Card card, Long viewerId,
                                            Map<Long, Application> applications,
                                            Map<Long, User> holders,
                                            Map<Long, PressCategory> categories,
                                            Map<Long, Session> sessions,
                                            Map<Long, RevocationProposal> pendingByCard) {
        Application application = card.getApplicationId() == null ? null
                : applications.get(card.getApplicationId());
        User holder = application == null ? null
                : holders.get(application.getCandidateId());
        PressCategory category = application == null ? null
                : categories.get(application.getCategoryId());
        Session session = application == null ? null
                : sessions.get(application.getSessionId());

        RevocationProposal pending = pendingByCard.get(card.getId());

        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        return new ReviewerCardResponse(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category == null ? "—" : category.getLabelFr(),
                card.getSpecialisationFr(),
                card.getInstitution(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                lapsed ? "EXPIRED" : card.getStatus().name(),
                lapsed ? "Expirée" : card.getStatus().labelFr(),
                expired,
                pending != null,
                pending != null && pending.getProposedBy().equals(viewerId),
                cannotProposeReason(card, pending, viewerId),
                session == null ? null : session.getId(),
                sessionLabel(session));
    }

    /** "Session du 12 mars 2026". */
    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
    }

    /**
     * Why a withdrawal may not be proposed, or null if it may.
     *
     * The same principle as the submission gate and the objection eligibility
     * object: the SERVER decides, and says why. A UI that works this out
     * itself is a second implementation of the rule, and the two will
     * eventually disagree about whether someone keeps their accreditation.
     */
    private String cannotProposeReason(Card card, RevocationProposal pending, Long viewerId) {
        if (card.getStatus() == CardStatus.REVOKED) {
            return "Cette carte a déjà été retirée.";
        }
        if (pending != null) {
            return pending.getProposedBy().equals(viewerId)
                    ? "Vous avez déjà proposé le retrait de cette carte. Elle est "
                      + "en attente de décision."
                    : "Une proposition de retrait est déjà en cours d'examen pour "
                      + "cette carte.";
        }
        // An expired card is deliberately NOT blocked. A withdrawal is a
        // finding about conduct, and HAPA may need it on the record whether or
        // not the card has since lapsed.
        return null;
    }
}