package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.honour.HonourCardRepository;
import com.presscard.press_accreditation.institutional.InstitutionRepository;
import com.presscard.press_accreditation.institutional.InstitutionRequestService;
import com.presscard.press_accreditation.institutional.InstitutionalCardRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The Ministry's figures, counted in the database.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS EXISTS BECAUSE THE DASHBOARD WAS DOWNLOADING THE REGISTER TO COUNT
 * IT.
 *
 * getRegistry() returns every card — number, holder, identity, dates — and
 * the page filtered that array in the browser. It worked: six hundred rows is
 * a couple of hundred kilobytes, and nobody noticed.
 *
 * It stops working quietly. At three thousand cards the Ministry's home page
 * becomes the slowest screen in the system, and the reason is invisible from
 * the page: it looks like a dashboard, and it is a full table scan shipped
 * over the network.
 *
 * ⚠️ AND IT COULD NOT ANSWER THE QUESTION ANYWAY.
 *
 * The register is series A. Honour cards and institutional cards live in
 * their own tables — so "how many cards are in force" had a partial answer
 * that looked complete, and adding two more registry downloads would have
 * tripled the cost to fix it.
 *
 * Eleven counts, eleven COUNT(*) queries, one small object.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/admin/stats")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminStatsController {

    /**
     * ⚠️ NINETY DAYS, matching the horizon the dashboard already used and the
     * one InstitutionalRenewalJob works to. Three numbers meaning "soon"
     * would eventually mean three different things.
     */
    private static final int LAPSE_HORIZON_DAYS = 90;

    /** One series, counted the same way for all three. */
    public record SeriesStats(
            /**
             * Every card of this series ever issued.
             *
             * ⚠️ NOT DERIVABLE FROM THE OTHERS, which is why it is here.
             *
             * A card can be in force, or expired, or revoked — and a revoked
             * card that has also passed its date is in two of those counts.
             * They overlap by design: each answers its own question. Only a
             * COUNT of the table answers "how many were ever issued".
             */
            long total,
            /** Granted, valid, and not past its expiry date. */
            long inForce,
            /** In force, and lapsing within the horizon. */
            long lapsingSoon,
            long suspended,
            long revoked,
            /** Past their date — whatever the status column says. */
            long expired
    ) {}

    public record AdminStats(
            SeriesStats candidacy,
            SeriesStats honour,
            SeriesStats institutional,
            /* ── the institutional picture ── */
            long institutionsActive,
            long institutionsTotal,
            /**
             * ⚠️ FILED BUT NOT GRANTED — the number that needs the Ministry.
             *
             * The three SeriesStats above say what exists. This says what is
             * waiting, and it is the only figure on this screen that is
             * somebody's turn.
             */
            long institutionalAwaitingGrant,
            /**
             * Demandes d'enregistrement confirmées, en attente d'examen.
             *
             * ───────────────────────────────────────────────────────────
             * ⚠️ PENDING_REVIEW SEULEMENT, et c'est le sens du chiffre.
             *
             * Une demande SUBMITTED n'a pas d'adresse confirmée : elle peut
             * avoir été déposée par n'importe qui, avec l'adresse de
             * n'importe qui. La compter ferait attendre le Ministère devant
             * un dossier qu'il ne doit pas encore lire.
             *
             * institutionalAwaitingGrant compte des fiches déposées par un
             * corps déjà enregistré. Les deux sont des attentes ; elles ne
             * portent pas sur la même chose, et le tableau de bord les
             * sépare.
             * ───────────────────────────────────────────────────────────
             */
            long institutionRequestsPending
    ) {}

    private final CardRepository cardRepository;
    private final HonourCardRepository honourCardRepository;
    private final InstitutionalCardRepository institutionalCardRepository;
    private final InstitutionRepository institutionRepository;
    private final InstitutionRequestService institutionRequestService;

    public AdminStatsController(CardRepository cardRepository,
                                HonourCardRepository honourCardRepository,
                                InstitutionalCardRepository institutionalCardRepository,
                                InstitutionRepository institutionRepository,
                                InstitutionRequestService institutionRequestService) {
        this.cardRepository = cardRepository;
        this.honourCardRepository = honourCardRepository;
        this.institutionalCardRepository = institutionalCardRepository;
        this.institutionRepository = institutionRepository;
        this.institutionRequestService = institutionRequestService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public AdminStats stats() {
        LocalDate horizon = LocalDate.now().plusDays(LAPSE_HORIZON_DAYS);

        return new AdminStats(

                new SeriesStats(
                        cardRepository.count(),
                        cardRepository.countInForce(),
                        cardRepository.countLapsingBefore(horizon),
                        cardRepository.countByStatus(CardStatus.SUSPENDED),
                        cardRepository.countByStatus(CardStatus.REVOKED),
                        cardRepository.countExpired()),

                new SeriesStats(
                        honourCardRepository.count(),
                        honourCardRepository.countInForce(),
                        honourCardRepository.countLapsingBefore(horizon),
                        honourCardRepository.countByStatus(CardStatus.SUSPENDED),
                        honourCardRepository.countByStatus(CardStatus.REVOKED),
                        honourCardRepository.countExpired()),

                new SeriesStats(
                        /* ⚠️ Les fiches non octroyées ne sont pas des cartes :
                           countGranted(), pas count(). */
                        institutionalCardRepository.countGranted(),
                        institutionalCardRepository.countInForce(),
                        institutionalCardRepository.countLapsingBefore(horizon),
                        institutionalCardRepository.countByStatus(CardStatus.SUSPENDED),
                        institutionalCardRepository.countByStatus(CardStatus.REVOKED),
                        institutionalCardRepository.countExpired()),

                institutionRepository.countByActiveTrue(),
                institutionRepository.count(),
                institutionalCardRepository.countAwaitingGrant(),
                institutionRequestService.pendingCount());
    }
}