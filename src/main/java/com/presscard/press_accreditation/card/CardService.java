package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Turning an accepted dossier into a credential.
 *
 * SIX PROPERTIES THAT MATTER FOR A DOCUMENT THAT LEAVES THE BUILDING.
 *
 * 1. THE NUMBER COMES FROM A SEQUENCE, not from MAX(number)+1. Two
 *    administrators generating batches at the same moment would otherwise read
 *    the same maximum and print two cards bearing the same number — on paper
 *    already in someone's pocket, that is unrecoverable.
 *
 * 2. EVERYTHING PRINTED IS SNAPSHOTTED — the photograph, the specialisation,
 *    the institution. A card is a dated document: if the holder moves to
 *    another outlet in 2027, the 2026 card must still say what it said when
 *    it was issued.
 *
 * 3. THE EXPIRY COMES FROM THE SESSION, not the issuance date. An
 *    accreditation runs in cycles — everyone accredited together renews
 *    together — and it is also what makes a single shared card back safe.
 *
 * 4. THE VERIFICATION TOKEN IS RANDOM AND OPAQUE. Were the QR to read
 *    /verifier/A-0042-26, anyone could iterate the range and harvest the
 *    identity and photograph of every accredited journalist in Mauritania.
 *
 * 5. EVERY PRECONDITION IS CHECKED BEFORE A NUMBER IS TAKEN. A sequence value
 *    consumed by a failed issuance leaves a permanent gap in the register.
 *
 * 6. A BATCH DOES NOT ABORT ON ONE FAILURE. Two hundred cards where one
 *    candidate has an unreadable photograph should produce 199 cards and a
 *    named failure, not nothing at all.
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger("CARD_AUDIT");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardRepository cardRepository;
    private final CardStatusHistoryRepository historyRepository;
    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final SpecialisationRepository specialisationRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final CardSigningService signingService;
    private final PhotoStorageService photoStorage;
    private final ApplicationService applicationService;
    private final EmailService emailService;
    private final AppProperties props;

    public CardService(CardRepository cardRepository,
                       CardStatusHistoryRepository historyRepository,
                       ApplicationRepository applicationRepository,
                       CandidateProfileRepository profileRepository,
                       SpecialisationRepository specialisationRepository,
                       SessionRepository sessionRepository,
                       UserRepository userRepository,
                       CardSigningService signingService,
                       PhotoStorageService photoStorage,
                       ApplicationService applicationService,
                       EmailService emailService,
                       AppProperties props) {
        this.cardRepository = cardRepository;
        this.historyRepository = historyRepository;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.specialisationRepository = specialisationRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.signingService = signingService;
        this.photoStorage = photoStorage;
        this.applicationService = applicationService;
        this.emailService = emailService;
        this.props = props;
    }

    /* ══ issuing ══════════════════════════════════════════════ */

    /**
     * Issue one card. Idempotent: a dossier that already has a card gets its
     * existing one back rather than a second number.
     */
    @Transactional
    public Card issue(Long applicationId, Long issuerId) {
        Card existing = cardRepository.findByApplicationId(applicationId).orElse(null);
        if (existing != null) {
            return existing;
        }

        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));

        if (application.getStatus() != ApplicationStatus.ACCEPTED) {
            throw new CardNotIssuableException(
                    "Seule une candidature acceptée peut donner lieu à une carte (%s)."
                            .formatted(application.getStatus().labelFr()));
        }

        User candidate = userRepository.findById(application.getCandidateId()).orElseThrow();
        CandidateProfile profile = profileRepository.findById(candidate.getId())
                .orElseThrow(() -> new CardNotIssuableException(
                        "Le profil du candidat est introuvable."));
        Session session = sessionRepository.findById(application.getSessionId())
                .orElseThrow(() -> new CardNotIssuableException(
                        "Session introuvable pour cette candidature."));

        /* ── property 5: everything checked BEFORE a number is taken ──
           A sequence value consumed by a failed issuance is a permanent gap in
           the register — small, but a register with unexplained holes invites
           the question of what was removed. */

        if (profile.getPhotoPath() == null) {
            throw new CardNotIssuableException(
                    "Aucune photographie : la carte de " + candidate.getFullName()
                  + " ne peut pas être éditée.");
        }
        if (application.getSpecialisationId() == null
                || application.getInstitution() == null
                || application.getInstitution().isBlank()) {
            // Both are printed on the card. The submission gate refuses a
            // dossier without them, so reaching here means a dossier submitted
            // before those fields existed.
            throw new CardNotIssuableException(
                    "Spécialité ou organe de presse manquant : la carte de "
                  + candidate.getFullName() + " ne peut pas être éditée.");
        }

        LocalDate issuedAt = LocalDate.now();
        LocalDate expiresAt = expiryFor(session, issuedAt);
        String cardNumber = nextCardNumber(issuedAt);

        // Persist first, so the card has an id the photo snapshot can name.
        Card card = cardRepository.save(Card.builder()
                .applicationId(applicationId)
                .cardNumber(cardNumber)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .verificationToken(newVerificationToken())
                .status(CardStatus.VALID)
                .issuedBy(issuerId)
                .build());

        /* ── property 2: the snapshots ── */

        // The photograph AS ISSUED — a copy, never a reference.
        card.setPhotoPath(photoStorage.snapshotForCard(profile.getPhotoPath(), card.getId()));

        // The employment details, for the same reason: the card must not change
        // when the holder moves outlet.
        specialisationRepository.findById(application.getSpecialisationId())
                .ifPresent(s -> {
                    card.setSpecialisationFr(s.getLabelFr());
                    card.setSpecialisationAr(s.getLabelAr());
                });
        card.setInstitution(application.getInstitution());

        /* ── the signature ── */

        String canonical = CardSigningService.canonicalForm(
                cardNumber,
                profile.getNni() != null ? profile.getNni() : profile.getPassportNo(),
                candidate.getFullName(),
                issuedAt.toString(),
                expiresAt.toString());

        card.setSignature(signingService.sign(canonical));
        card.setSignatureKeyId(signingService.currentKeyId());
        cardRepository.save(card);

        applicationService.transition(application, ApplicationStatus.CARD_ISSUED,
                issuerId, "Carte n° " + cardNumber + " éditée.");

        emailService.sendCardIssued(candidate.getId(), applicationId, cardNumber, expiresAt);

        log.info("CARD_ISSUED number={} application={} session={} issuer={} expires={}",
                cardNumber, applicationId, session.getId(), issuerId, expiresAt);
        return card;
    }

    /** One card's outcome inside a batch. */
    public record IssueOutcome(
            Long applicationId,
            String candidateFullName,
            boolean issued,
            String cardNumber,
            String failureReason
    ) {}

    public record BatchResult(
            int requested,
            int issued,
            int failed,
            List<IssueOutcome> outcomes
    ) {}

    /**
     * Issue many.
     *
     * Each card is attempted independently: one candidate with an unreadable
     * photograph must not cost the other 199 their cards. Failures are NAMED
     * so an administrator can fix them and re-run — the operation is
     * idempotent, so re-running is safe.
     */
    @Transactional
    public BatchResult issueMany(List<Long> applicationIds, Long issuerId) {
        List<IssueOutcome> outcomes = new ArrayList<>();
        int issued = 0;
        int failed = 0;

        for (Long applicationId : applicationIds) {
            String name = candidateNameOf(applicationId);
            try {
                Card card = issue(applicationId, issuerId);
                outcomes.add(new IssueOutcome(applicationId, name, true,
                        card.getCardNumber(), null));
                issued++;
            } catch (RuntimeException e) {
                // Named, not swallowed: the administrator must know WHO failed
                // and WHY, or the batch is a black box.
                outcomes.add(new IssueOutcome(applicationId, name, false, null,
                        e.getMessage()));
                failed++;
                log.warn("CARD_ISSUE_FAILED application={} candidate={} reason={}",
                        applicationId, name, e.getMessage());
            }
        }

        log.info("CARD_BATCH issuer={} requested={} issued={} failed={}",
                issuerId, applicationIds.size(), issued, failed);
        return new BatchResult(applicationIds.size(), issued, failed, outcomes);
    }

    /* ══ verification ═════════════════════════════════════════ */

    /** What a scan resolves to. Deliberately narrow — see the controller. */
//    public record VerificationResult(
//            boolean found,
//            String status,              // VALID | SUSPENDED | REVOKED | EXPIRED
//            String statusLabelFr,
//            String statusLabelAr,
//            boolean usable,
//            String cardNumber,
//            String holderFullName,
//            String categoryLabelFr,
//            LocalDate issuedAt,
//            LocalDate expiresAt,
//            boolean signatureValid,
//            /** Set when suspended, revoked or expired. */
//            String statusNoteFr
//    ) {}

    public record VerificationResult(
            boolean found,
            String status,
            String statusLabelFr,
            String statusLabelAr,
            boolean usable,
            String cardNumber,
            String holderFullName,
            String categoryLabelFr,
            String categoryLabelAr,
            LocalDate issuedAt,
            LocalDate expiresAt,
            boolean signatureValid,
            String statusNoteFr,
            String statusNoteAr
    )  {

        /**
         * The answer to an unknown token.
         *
         * A named factory rather than fourteen positional nulls: the previous
         * form broke twice while two fields were added, and a mis-ordered null
         * in this record would silently report a card as unsigned.
         */
        public static VerificationResult notFound() {
            return new VerificationResult(
                    false, null, null, null, false, null, null,
                    null, null, null, null, false, null, null);
        }
    }

    @Transactional(readOnly = true)
    public VerificationResult verify(String token) {
//        Card card = cardRepository.findByVerificationToken(token).orElse(null);
//        if (card == null) {
//            return new VerificationResult(false, null, null, null, false,
//                    null, null, null, null, null, false, null);
//        }

        Card card = cardRepository.findByVerificationToken(token).orElse(null);
        if (card == null) {
            // 14 nulls-and-falses, in the record's order. An unknown token
            // discloses nothing beyond "not found" — the page supplies its own
            // wording from the catalogue.
            return VerificationResult.notFound();
        }

        Application application = applicationRepository
                .findById(card.getApplicationId()).orElse(null);
        User holder = application == null ? null
                : userRepository.findById(application.getCandidateId()).orElse(null);
        CandidateProfile profile = holder == null ? null
                : profileRepository.findById(holder.getId()).orElse(null);

        // EXPIRED is computed here, never stored — so a lapsed card can never
        // read "valide" because a job failed to run.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        String status = lapsed ? "EXPIRED" : card.getStatus().name();
        String labelFr = lapsed ? "Expirée" : card.getStatus().labelFr();
        String labelAr = lapsed ? "منتهية الصلاحية" : card.getStatus().labelAr();

        boolean signatureValid = holder != null && signingService.verify(
                CardSigningService.canonicalForm(
                        card.getCardNumber(),
                        profile == null ? null
                                : (profile.getNni() != null
                                        ? profile.getNni() : profile.getPassportNo()),
                        holder.getFullName(),
                        card.getIssuedAt().toString(),
                        card.getExpiresAt().toString()),
                card.getSignature());

//        return new VerificationResult(
//                true, status, labelFr, labelAr,
//                card.isUsable(),
//                card.getCardNumber(),
//                holder == null ? null : holder.getFullName(),
//                null,                       // category label filled by the controller
//                card.getIssuedAt(),
//                card.getExpiresAt(),
//                signatureValid,
//                switch (card.getStatus()) {
//                    case SUSPENDED -> "Cette carte est temporairement suspendue par le MCACRP.";
//                    case REVOKED -> "Cette carte a été retirée par le MCACRP et n'est plus valable.";
//                    case VALID -> expired ? "Cette carte est arrivée à échéance." : null;
//                });

        return new VerificationResult(
                true, status, labelFr, labelAr,
                card.isUsable(),
                card.getCardNumber(),
                holder == null ? null : holder.getFullName(),
                null,                       // category labels filled by the controller
                null,
                card.getIssuedAt(),
                card.getExpiresAt(),
                signatureValid,
                switch (card.getStatus()) {
                    case SUSPENDED -> "Cette carte est temporairement suspendue par le Ministère.";
                    case REVOKED   -> "Cette carte a été retirée par le Ministère et n'est plus valable.";
                    case VALID     -> expired ? "Cette carte est arrivée à échéance." : null;
                },
                switch (card.getStatus()) {
                    case SUSPENDED -> "هذه البطاقة موقوفة مؤقتًا من طرف الوزارة.";
                    case REVOKED   -> "سحبت الوزارة هذه البطاقة ولم تعد صالحة.";
                    case VALID     -> expired ? "بلغت هذه البطاقة أجلها." : null;
                });
    }

    /* ══ internals ════════════════════════════════════════════ */

    /**
     * The last day the card is valid, inclusive.
     *
     * TAKEN FROM THE SESSION — property 3. An accreditation runs in cycles:
     * everyone accredited together holds a card for the same period and
     * renews together. Computed per card, a journalist issued in March would
     * renew in March and one issued in August in August, turning a
     * once-a-cycle act into a continuous one.
     *
     * The per-card fallback exists only for sessions created before V13. It
     * logs at WARN rather than passing silently, so a session missing its
     * expiry is visible rather than merely tolerated.
     */
    private LocalDate expiryFor(Session session, LocalDate issuedAt) {
        if (session.getCardExpiryDate() != null) {
            return session.getCardExpiryDate();
        }
        log.warn("SESSION_WITHOUT_CARD_EXPIRY session={} — falling back to {} days",
                session.getId(), props.card().validityDays());
        return issuedAt.plusDays(props.card().validityDays()).minusDays(1);
    }

    /**
     * A - 0001 / 26 — series letter, four-digit sequence, two-digit year.
     *
     * From a SEQUENCE, see property 1. The year comes from the issuance date,
     * and the sequence is reset each January as part of the year-opening
     * runbook.
     */
    private String nextCardNumber(LocalDate issuedAt) {
        Long next = cardRepository.nextCardNumber();
        return "%s - %04d / %02d".formatted(
                props.card().numberSeries(),
                next,
                issuedAt.getYear() % 100);
    }

    /** 128 bits of randomness — guessing one is infeasible. */
    private String newVerificationToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String candidateNameOf(Long applicationId) {
        return applicationRepository.findById(applicationId)
                .flatMap(a -> userRepository.findById(a.getCandidateId()))
                .map(User::getFullName)
                .orElse("—");
    }
}
