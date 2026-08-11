package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

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
            String cannotProposeReasonFr
    ) {}

    private final CardRepository cardRepository;
    private final RevocationProposalRepository proposalRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public ReviewerCardController(CardRepository cardRepository,
                                  RevocationProposalRepository proposalRepository,
                                  ApplicationRepository applicationRepository,
                                  PressCategoryRepository categoryRepository,
                                  UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.proposalRepository = proposalRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    /** Every issued card, newest first. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<ReviewerCardResponse> cards(java.security.Principal principal) {
        Long me = userRepository.findByEmail(principal.getName()).orElseThrow().getId();

        return cardRepository.findAllByOrderByIssuedAtDesc().stream()
                .map(card -> toResponse(card, me))
                .toList();
    }

    private ReviewerCardResponse toResponse(Card card, Long viewerId) {
        Application application = card.getApplicationId() == null ? null
                : applicationRepository.findById(card.getApplicationId()).orElse(null);
        User holder = application == null ? null
                : userRepository.findById(application.getCandidateId()).orElse(null);
        String category = application == null ? "—"
                : categoryRepository.findById(application.getCategoryId())
                        .map(PressCategory::getLabelFr).orElse("—");

        RevocationProposal pending = proposalRepository
                .findByCardIdAndStatus(card.getId(), RevocationProposal.Status.PENDING)
                .orElse(null);

        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        return new ReviewerCardResponse(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category,
                card.getSpecialisationFr(),
                card.getInstitution(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                lapsed ? "EXPIRED" : card.getStatus().name(),
                lapsed ? "Expirée" : card.getStatus().labelFr(),
                expired,
                pending != null,
                pending != null && pending.getProposedBy().equals(viewerId),
                cannotProposeReason(card, pending, viewerId));
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
