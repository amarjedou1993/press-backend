package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * What happens to a card after it is issued: suspension, revocation,
 * reinstatement.
 *
 * FIVE RULES, and the first two are the design.
 *
 * 1. SUSPENSION IS THE AUTHORITY'S ALONE, and immediate. It is precautionary
 *    and REVERSIBLE — a card reported stolen, a holder under investigation.
 *    Requiring a committee while a stolen card circulates helps nobody, and
 *    the act can be undone if it turns out to be unwarranted.
 *
 * 2. REVOCATION TAKES TWO HANDS. A commission member proposes with a ground
 *    and a statement; the Authority executes or declines. That mirrors how the
 *    card was granted — the commission decided entitlement, the Authority
 *    issued it — and it is what makes a withdrawal defensible if challenged.
 *    A super admin acting alone can be characterised as an administrative act
 *    against a journalist; a commission proposal executed by the Authority
 *    cannot.
 *
 * 3. REVOCATION IS TERMINAL. A revoked card is never reinstated: if the
 *    holder should hold a card again, they apply in the next session and the
 *    commission examines them afresh. Reinstating a revoked card would mean
 *    the withdrawal had been provisional all along, which is not what it says
 *    to whoever scanned it in the meantime.
 *
 * 4. EVERY CHANGE CARRIES AN ACTOR, A REASON AND A TIMESTAMP — in
 *    card_status_history, and enforced by a CHECK on the card row. For a
 *    regulator, the record of a withdrawal is the defence of the withdrawal.
 *
 * 5. THE HOLDER IS TOLD. A journalist whose card stops working at a checkpoint
 *    without warning has been treated badly, whatever the merits.
 */
@Service
public class CardLifecycleService {

    private static final Logger log = LoggerFactory.getLogger("CARD_LIFECYCLE");

    /** Enough that the Authority can act on it rather than guess. */
    private static final int MIN_STATEMENT_LENGTH = 40;

    private final CardRepository cardRepository;
    private final CardStatusHistoryRepository historyRepository;
    private final RevocationProposalRepository proposalRepository;
    private final RevocationGroundRepository groundRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public CardLifecycleService(CardRepository cardRepository,
                                CardStatusHistoryRepository historyRepository,
                                RevocationProposalRepository proposalRepository,
                                RevocationGroundRepository groundRepository,
                                ApplicationRepository applicationRepository,
                                UserRepository userRepository,
                                EmailService emailService) {
        this.cardRepository = cardRepository;
        this.historyRepository = historyRepository;
        this.proposalRepository = proposalRepository;
        this.groundRepository = groundRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    /* ══ suspension — the Authority alone ══════════════════════ */

    /**
     * Withhold a card temporarily.
     *
     * Rule 1: immediate, and reversible. The reason is mandatory because the
     * verification page will show the holder's card as suspended to anyone who
     * scans it, and the holder is entitled to know why.
     */
    @Transactional
    public Card suspend(Long cardId, Long actorId, String reason) {
        Card card = find(cardId);

        if (card.getStatus() == CardStatus.REVOKED) {
            throw new CardLifecycleException(
                    "Cette carte a été retirée : elle ne peut plus être suspendue.");
        }
        if (card.getStatus() == CardStatus.SUSPENDED) {
            throw new CardLifecycleException("Cette carte est déjà suspendue.");
        }
        requireReason(reason, "Indiquez le motif de la suspension.");

        transition(card, CardStatus.SUSPENDED, actorId, reason.trim(), null);
        notifyHolder(card, CardStatus.SUSPENDED, reason.trim());

        log.warn("CARD_SUSPENDED number={} actor={} reason={}",
                card.getCardNumber(), actorId, reason.trim());
        return card;
    }

    /**
     * Put a suspended card back in force.
     *
     * Only from SUSPENDED — see rule 3. A revoked card is never reinstated.
     */
    @Transactional
    public Card reinstate(Long cardId, Long actorId, String reason) {
        Card card = find(cardId);

        if (card.getStatus() == CardStatus.REVOKED) {
            throw new CardLifecycleException(
                    "Une carte retirée ne peut pas être rétablie. Le titulaire doit "
                  + "déposer une nouvelle candidature lors d'une prochaine session.");
        }
        if (card.getStatus() == CardStatus.VALID) {
            throw new CardLifecycleException("Cette carte est déjà valide.");
        }
        requireReason(reason, "Indiquez le motif du rétablissement.");

        transition(card, CardStatus.VALID, actorId, reason.trim(), null);
        notifyHolder(card, CardStatus.VALID, reason.trim());

        log.info("CARD_REINSTATED number={} actor={} reason={}",
                card.getCardNumber(), actorId, reason.trim());
        return card;
    }

    /* ══ revocation — two hands ════════════════════════════════ */

    /**
     * A commission member proposes that a card be withdrawn.
     *
     * Rule 2: this does NOT withdraw it. The Authority decides. But where the
     * ground warrants it — a forged dossier, a card used for something other
     * than journalism — the card is SUSPENDED immediately, because such a card
     * should not stay in force for the days a decision takes.
     */
    @Transactional
    public RevocationProposal propose(Long cardId, Long proposerId,
                                      Long groundId, String statement) {
        Card card = find(cardId);

        if (card.getStatus() == CardStatus.REVOKED) {
            throw new CardLifecycleException("Cette carte a déjà été retirée.");
        }
        // Backed by a partial unique index — checked here so the message
        // explains rather than surfacing a constraint name.
        if (proposalRepository.existsByCardIdAndStatus(
                cardId, RevocationProposal.Status.PENDING)) {
            throw new CardLifecycleException(
                    "Une proposition de retrait est déjà en cours d'examen pour cette carte.");
        }

        RevocationGround ground = groundRepository.findById(groundId)
                .filter(RevocationGround::isActive)
                .orElseThrow(() -> new CardLifecycleException("Motif de retrait invalide."));

        if (statement == null || statement.trim().length() < MIN_STATEMENT_LENGTH) {
            throw new CardLifecycleException(
                    ("Exposez les faits en %d caractères au minimum. L'autorité qui "
                   + "décidera du retrait doit pouvoir se prononcer sur ce que vous "
                   + "écrivez.").formatted(MIN_STATEMENT_LENGTH));
        }

        RevocationProposal proposal = proposalRepository.save(RevocationProposal.builder()
                .cardId(cardId)
                .groundId(groundId)
                .statement(statement.trim())
                .proposedBy(proposerId)
                .status(RevocationProposal.Status.PENDING)
                .build());

        // A card alleged to be fraudulent or misused should not stay in force
        // while the proposal is examined.
        if (ground.isWarrantsImmediateSuspension()
                && card.getStatus() == CardStatus.VALID) {
            transition(card, CardStatus.SUSPENDED, proposerId,
                    "Suspension conservatoire — proposition de retrait n° "
                            + proposal.getId() + " : " + ground.getLabelFr(),
                    proposerId);
            notifyHolder(card, CardStatus.SUSPENDED,
                    "Suspension conservatoire dans l'attente d'une décision.");
        }

        emailService.sendRevocationProposed(proposal.getId(),
                card.getCardNumber(), ground.getLabelFr());

        log.warn("CARD_REVOCATION_PROPOSED proposal={} card={} ground={} by={}",
                proposal.getId(), card.getCardNumber(), ground.getCode(), proposerId);
        return proposal;
    }

    /** The proposer changes their mind before the Authority decides. */
    @Transactional
    public RevocationProposal withdrawProposal(Long proposalId, Long proposerId) {
        RevocationProposal proposal = findProposal(proposalId);

        if (!proposal.getStatus().isOpen()) {
            throw new CardLifecycleException(
                    "Cette proposition n'est plus en cours d'examen.");
        }
        if (!proposal.getProposedBy().equals(proposerId)) {
            throw new CardLifecycleException(
                    "Seul l'auteur d'une proposition peut la retirer.");
        }

        proposal.setStatus(RevocationProposal.Status.WITHDRAWN);
        proposalRepository.save(proposal);

        // The precautionary suspension goes with it — the allegation no longer
        // stands, so the card should not keep bearing its consequence.
        Card card = find(proposal.getCardId());
        if (card.getStatus() == CardStatus.SUSPENDED) {
            transition(card, CardStatus.VALID, proposerId,
                    "Proposition de retrait n° " + proposalId + " retirée par son auteur.",
                    null);
            notifyHolder(card, CardStatus.VALID,
                    "La procédure engagée à votre encontre a été abandonnée.");
        }

        log.info("CARD_REVOCATION_WITHDRAWN proposal={} by={}", proposalId, proposerId);
        return proposal;
    }

    /**
     * The Authority withdraws the card.
     *
     * Terminal. The proposal, its ground and its author are all recorded on the
     * status-history row — so the record shows BOTH HANDS, which is the point
     * of requiring two.
     */
    @Transactional
    public Card executeRevocation(Long proposalId, Long actorId, String note) {
        RevocationProposal proposal = findProposal(proposalId);

        if (!proposal.getStatus().isOpen()) {
            throw new CardLifecycleException(
                    "Cette proposition a déjà été traitée (%s)."
                            .formatted(proposal.getStatus().labelFr()));
        }
        // The Authority executes; it does not propose to itself. A member who
        // is also an administrator must still have a colleague's proposal.
        if (proposal.getProposedBy().equals(actorId)) {
            throw new CardLifecycleException(
                    "Vous ne pouvez pas exécuter une proposition dont vous êtes "
                  + "l'auteur : le retrait d'une carte exige deux intervenants "
                  + "distincts.");
        }

        Card card = find(proposal.getCardId());
        RevocationGround ground = groundRepository.findById(proposal.getGroundId())
                .orElseThrow();

        String reason = """
                Retrait prononcé sur proposition de la commission.

                Motif : %s

                Exposé : %s%s"""
                .formatted(
                        ground.getLabelFr(),
                        proposal.getStatement(),
                        note == null || note.isBlank() ? "" : "\n\nObservation : " + note.trim());

        transition(card, CardStatus.REVOKED, actorId, reason, proposal.getProposedBy());

        proposal.setStatus(RevocationProposal.Status.EXECUTED);
        proposal.setDecidedBy(actorId);
        proposal.setDecidedAt(OffsetDateTime.now());
        proposal.setDecidedNote(note == null || note.isBlank() ? null : note.trim());
        proposalRepository.save(proposal);

        notifyHolder(card, CardStatus.REVOKED, reason);

        log.warn("CARD_REVOKED number={} proposal={} ground={} proposedBy={} executedBy={}",
                card.getCardNumber(), proposalId, ground.getCode(),
                proposal.getProposedBy(), actorId);
        return card;
    }

    /**
     * The Authority declines.
     *
     * A note is REQUIRED — enforced by a CHECK as well. A refusal the proposer
     * cannot read is a refusal they will simply repeat.
     */
    @Transactional
    public RevocationProposal declineRevocation(Long proposalId, Long actorId, String note) {
        RevocationProposal proposal = findProposal(proposalId);

        if (!proposal.getStatus().isOpen()) {
            throw new CardLifecycleException(
                    "Cette proposition a déjà été traitée (%s)."
                            .formatted(proposal.getStatus().labelFr()));
        }
        requireReason(note, "Indiquez le motif du refus : l'auteur de la proposition "
                          + "doit pouvoir en tenir compte.");

        proposal.setStatus(RevocationProposal.Status.DECLINED);
        proposal.setDecidedBy(actorId);
        proposal.setDecidedAt(OffsetDateTime.now());
        proposal.setDecidedNote(note.trim());
        proposalRepository.save(proposal);

        // The precautionary suspension is lifted: the allegation was not upheld.
        Card card = find(proposal.getCardId());
        if (card.getStatus() == CardStatus.SUSPENDED) {
            transition(card, CardStatus.VALID, actorId,
                    "Proposition de retrait n° " + proposalId + " rejetée : " + note.trim(),
                    null);
            notifyHolder(card, CardStatus.VALID,
                    "La procédure engagée à votre encontre n'a pas abouti.");
        }

        log.info("CARD_REVOCATION_DECLINED proposal={} by={}", proposalId, actorId);
        return proposal;
    }

    /* ══ reading ═══════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public List<RevocationGround> activeGrounds() {
        return groundRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    @Transactional(readOnly = true)
    public List<RevocationProposal> pendingProposals() {
        return proposalRepository.findByStatusOrderByProposedAtAsc(
                RevocationProposal.Status.PENDING);
    }

    @Transactional(readOnly = true)
    public List<RevocationProposal> proposalsFor(Long cardId) {
        return proposalRepository.findByCardIdOrderByProposedAtDesc(cardId);
    }

    @Transactional(readOnly = true)
    public List<CardStatusHistory> historyFor(Long cardId) {
        return historyRepository.findByCardIdOrderByCreatedAtDesc(cardId);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return proposalRepository.countPending();
    }

    /* ══ internals ═════════════════════════════════════════════ */

    /**
     * The ONLY way a card's status changes.
     *
     * Writes the card row and the history row in the same transaction, so the
     * record can never drift from the state — the same discipline as
     * ApplicationService.transition, and for the same reason: the audit trail
     * IS the product for a regulator.
     */
    private void transition(Card card, CardStatus target, Long actorId,
                            String reason, Long proposedBy) {
        CardStatus from = card.getStatus();

        card.setStatus(target);
        card.setStatusChangedAt(OffsetDateTime.now());
        card.setStatusChangedBy(actorId);
        card.setStatusReason(reason);
        cardRepository.save(card);

        historyRepository.save(CardStatusHistory.builder()
                .cardId(card.getId())
                .fromStatus(from)
                .toStatus(target)
                .reason(reason)
                .actorId(actorId)
                .proposedBy(proposedBy)
                .build());
    }

    /** Rule 5 — the holder is told, every time. */
    private void notifyHolder(Card card, CardStatus status, String reason) {
        applicationRepository.findById(card.getApplicationId())
                .map(Application::getCandidateId)
                .ifPresent(holderId -> emailService.sendCardStatusChanged(
                        holderId, card.getCardNumber(), status.name(),
                        status.labelFr(), reason));
    }

    private void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new CardLifecycleException(message);
        }
    }

    private Card find(Long cardId) {
        return cardRepository.findById(cardId)
                .orElseThrow(() -> new CardLifecycleException("Carte introuvable."));
    }

    private RevocationProposal findProposal(Long proposalId) {
        return proposalRepository.findById(proposalId)
                .orElseThrow(() -> new CardLifecycleException("Proposition introuvable."));
    }
}
