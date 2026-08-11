package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Turns a RevocationProposal into something a person can read.
 *
 * EXTRACTED because both controllers need it — the commission member listing
 * their own proposals and the Authority working its queue — and a mapping
 * duplicated across two classes is a mapping that will eventually disagree
 * with itself. In the record of a withdrawal, that disagreement would matter.
 *
 * The join is four hops: proposal → card → application → holder, plus the
 * ground and up to two user names. Deliberately assembled HERE rather than
 * with JPA relations on the entity: the card and application tables are joined
 * by id rather than by @ManyToOne throughout this codebase, and introducing
 * one relation just for a DTO would make the entity graph inconsistent.
 */
@Component
public class RevocationProposalMapper {

    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final RevocationGroundRepository groundRepository;
    private final UserRepository userRepository;

    public RevocationProposalMapper(CardRepository cardRepository,
                                    ApplicationRepository applicationRepository,
                                    RevocationGroundRepository groundRepository,
                                    UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.groundRepository = groundRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public ProposalResponse toResponse(RevocationProposal proposal) {
        Card card = cardRepository.findById(proposal.getCardId()).orElse(null);
        RevocationGround ground = groundRepository
                .findById(proposal.getGroundId()).orElse(null);

        String holderName = card == null ? "—" : holderNameOf(card);

        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean lapsed = card != null
                && card.isExpired()
                && card.getStatus() == CardStatus.VALID;

        return new ProposalResponse(
                proposal.getId(),

                proposal.getCardId(),
                card == null ? "—" : card.getCardNumber(),
                holderName,
                card == null ? null : (lapsed ? "EXPIRED" : card.getStatus().name()),
                card == null ? null : (lapsed ? "Expirée" : card.getStatus().labelFr()),

                proposal.getGroundId(),
                ground == null ? null : ground.getCode(),
                ground == null ? "—" : ground.getLabelFr(),
                ground == null ? null : ground.getLabelAr(),
                ground != null && ground.isWarrantsImmediateSuspension(),
                proposal.getStatement(),

                proposal.getProposedBy(),
                nameOf(proposal.getProposedBy()),
                iso(proposal.getProposedAt()),

                proposal.getStatus().name(),
                proposal.getStatus().labelFr(),
                proposal.getDecidedBy() == null ? null : nameOf(proposal.getDecidedBy()),
                iso(proposal.getDecidedAt()),
                proposal.getDecidedNote());
    }

    @Transactional(readOnly = true)
    public List<ProposalResponse> toResponses(List<RevocationProposal> proposals) {
        return proposals.stream().map(this::toResponse).toList();
    }

    /* ── internals ── */

    /**
     * The holder, through the dossier the card was issued from.
     *
     * Card → application → candidate. A card with no application is possible
     * only in a test fixture, so "—" rather than an exception: a mapper that
     * throws makes an admin queue unreadable because of one odd row.
     */
    private String holderNameOf(Card card) {
        if (card.getApplicationId() == null) {
            return "—";
        }
        return applicationRepository.findById(card.getApplicationId())
                .map(Application::getCandidateId)
                .map(this::nameOf)
                .orElse("—");
    }

    private String nameOf(Long userId) {
        return userId == null ? "—"
                : userRepository.findById(userId)
                        .map(User::getFullName)
                        .orElse("—");
    }

    /** ISO-8601, formatted in the browser — the server does not know the locale. */
    private String iso(OffsetDateTime at) {
        return at == null ? null : at.toString();
    }
}
