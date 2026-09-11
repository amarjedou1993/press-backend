package com.presscard.press_accreditation.renewal;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.card.Card;
import com.presscard.press_accreditation.card.CardRepository;
import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.NotEligibleForRenewalException;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.session.SessionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Who may renew, and on what card.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ A RENEWAL IS A CANDIDATURE. THIS SERVICE ONLY DECIDES ELIGIBILITY.
 *
 * Everything after the decision — the dossier, the commission, the decision,
 * the card — runs through the paths that already exist. This class answers
 * one question: does this person hold a card that this session is for?
 *
 * It is a separate service rather than a method on ApplicationService because
 * the rule is a DATE WINDOW WITH A GRACE PERIOD, and that is the sort of rule
 * that ends up written three times in three places if it has no home.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class RenewalService {

    private static final Logger log = LoggerFactory.getLogger("RENEWAL_AUDIT");

    /**
     * How long after expiry a holder may still renew rather than re-apply.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ ONE FULL CYCLE, AND THE CARD IS INVALID THROUGHOUT IT.
     *
     * Almost nobody who lapses is a fraud — they were on assignment, or ill,
     * or missed one e-mail. Making them re-apply means producing a birth
     * certificate to prove an identity the Authority verified two years ago,
     * which is what an administration that does not believe its own records
     * asks for.
     *
     * But the grace period is ADMINISTRATIVE, not a licence: expiry is
     * expiry, and a scan reads "expirée" the day after. What the window buys
     * is a lighter dossier, not a working credential.
     *
     * ⚠️ And it is bounded. After two years unrenewed, the assumption that
     * someone is still a working journalist has genuinely stopped holding,
     * and a full candidature is the honest answer.
     * ───────────────────────────────────────────────────────────────────
     */
    private final int graceDays;

    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final SessionRepository sessionRepository;
    private final EmailService emailService;

    public RenewalService(CardRepository cardRepository,
                          ApplicationRepository applicationRepository,
                          SessionRepository sessionRepository,
                          EmailService emailService,
                          AppProperties props) {
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.sessionRepository = sessionRepository;
        this.emailService = emailService;
        this.graceDays = props.card().renewalGraceDays();
    }

    /** What the candidate's screen needs to know, and why. */
    public record RenewalEligibility(
            boolean eligible,
            /** The card being renewed, when there is one. */
            Long cardId,
            String cardNumber,
            LocalDate expiresAt,
            /** True when the card has already lapsed but is inside the grace period. */
            boolean lapsed,
            /** The open renewal session, when there is one. */
            Long sessionId,
            LocalDate sessionDeadline,
            /**
             * Null when eligible; otherwise why not.
             *
             * ⚠️ THE SERVER DECIDES AND SAYS WHY, as with the submission gate
             * and the objection window. A screen that works this out itself
             * is a second copy of a rule that decides whether someone keeps
             * their accreditation.
             */
            String blockerFr
    ) {
        static RenewalEligibility no(String blockerFr) {
            return new RenewalEligibility(false, null, null, null, false, null, null, blockerFr);
        }
    }

    /* ══ eligibility ══ */

    @Transactional(readOnly = true)
    public RenewalEligibility eligibilityFor(Long candidateId) {
        Session session = openRenewalSession().orElse(null);
        if (session == null) {
            return RenewalEligibility.no(
                    "Aucune session de renouvellement n'est ouverte actuellement.");
        }

        Card card = renewableCardOf(candidateId).orElse(null);
        if (card == null) {
            return RenewalEligibility.no(
                    "Le renouvellement est réservé aux titulaires d'une carte en cours "
                  + "ou expirée depuis moins d'un an. Vous pouvez déposer une "
                  + "candidature ordinaire lors de la prochaine session.");
        }

        /*
         * ⚠️ ALREADY REPLACED? THEN IT IS NOT RENEWABLE — THE SUCCESSOR IS.
         *
         * The database enforces one renewal per card, so a second attempt
         * would fail at insert with a constraint name. Answering here means
         * the candidate reads a sentence instead.
         */
        if (cardRepository.existsByRenewedFromCardId(card.getId())) {
            return RenewalEligibility.no(
                    "Cette carte a déjà été renouvelée.");
        }

        // A dossier already open in this session: the screen sends them to it
        // rather than offering to start a second.
        boolean already = applicationRepository
                .existsByCandidateIdAndSessionId(candidateId, session.getId());
        if (already) {
            return RenewalEligibility.no(
                    "Vous avez déjà une demande de renouvellement en cours pour "
                  + "cette session.");
        }

        boolean lapsed = card.isExpired();

        return new RenewalEligibility(
                true,
                card.getId(),
                card.getCardNumber(),
                card.getExpiresAt(),
                lapsed,
                session.getId(),
                session.getReceivingEnd(),
                null);
    }

    /**
     * The card this candidate may renew, if any.
     *
     * ⚠️ THE MOST RECENT ONE. A journalist accredited over several cycles has
     * several cards in the register; only the latest is the one in their
     * pocket, and renewing an older one would replace a card that has already
     * been replaced.
     */
    @Transactional(readOnly = true)
    public Optional<Card> renewableCardOf(Long candidateId) {
        LocalDate floor = LocalDate.now().minusMonths(graceDays);

        return cardRepository.findRenewableByCandidate(candidateId, CardStatus.VALID, floor);
    }

    /** The renewal session currently receiving dossiers, if there is one. */
    @Transactional(readOnly = true)
    public Optional<Session> openRenewalSession() {
        return sessionRepository.findFirstByTypeAndStatusOrderByStartDateDesc(
                SessionType.RENEWAL, SessionStatus.RECEIVING);
    }

    /**
     * Check eligibility and throw if it fails.
     *
     * ⚠️ CALLED WHEN A RENEWAL DOSSIER IS STARTED, not only when the screen
     * is drawn. The screen is a convenience; this is the boundary — a request
     * carries a session id, and nothing stops it being sent by someone the
     * screen never offered it to.
     */
    @Transactional(readOnly = true)
    public Card requireEligible(Long candidateId) {
        RenewalEligibility e = eligibilityFor(candidateId);
        if (!e.eligible()) {
            throw new NotEligibleForRenewalException(e.blockerFr());
        }
        return cardRepository.findById(e.cardId()).orElseThrow();
    }

    /* ══ the invitation ══ */

    /**
     * Tell every eligible holder that the window is open.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ CALLED WHEN A RENEWAL SESSION OPENS, and once only.
     *
     * This is the whole reason a holder knows to renew at all — nobody
     * watches a website for a session they have no reason to expect. An
     * accreditation that lapses because its holder was never told is an
     * administrative failure, not a candidate's.
     *
     * ⚠️ QUEUED THROUGH EmailService, so the outbox worker sends them. Two
     * hundred messages in the request that opened the session would hold the
     * transaction open for minutes and time out the administrator's click.
     * ───────────────────────────────────────────────────────────────────
     *
     * @return how many were invited — the caller logs it in its own terms
     */
    @Transactional
    public int inviteEligibleHolders(Session session) {
        if (session.getType() != SessionType.RENEWAL) {
            return 0;
        }

        LocalDate floor = LocalDate.now().minusMonths(graceDays);
        List<Card> cards = cardRepository.findAllRenewable(CardStatus.VALID, floor);

        int invited = 0;
        for (Card card : cards) {
            // Already replaced: its holder has a current card and nothing to do.
            if (cardRepository.existsByRenewedFromCardId(card.getId())) {
                continue;
            }
            Application application = applicationRepository
                    .findById(card.getApplicationId()).orElse(null);
            if (application == null) {
                log.warn("RENEWAL_INVITE_SKIPPED card={} — no application",
                        card.getCardNumber());
                continue;
            }

            emailService.sendRenewalInvitation(
                    application.getCandidateId(),
                    card.getCardNumber(),
                    card.getExpiresAt(),
                    session.getReceivingEnd());
            invited++;
        }

        log.info("RENEWAL_INVITATIONS session={} invited={}", session.getId(), invited);
        return invited;
    }
}
