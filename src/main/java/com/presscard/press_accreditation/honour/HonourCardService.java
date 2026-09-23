package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardSigningService;
import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.card.PrintRunRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Granting, and withdrawing, cards that no commission examined.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE SIGNATURE IS THE POINT OF THIS SERVICE.
 *
 * Everything else here is a form being saved. The signature is what makes the
 * card verifiable — computed over the SAME canonical form an ordinary card
 * uses, with the same key, so a scan cannot tell that this one skipped the
 * examination.
 *
 * Without it, `signatureValid` comes back false and the verification page
 * reports the Ministry's own card as unverifiable. Not "unknown". Suspect.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class HonourCardService {

    private static final Logger log = LoggerFactory.getLogger("HONOUR_CARD_AUDIT");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The series letter. Fixed, unlike the A series' configurable one. */
    private static final String SERIES = "B";

    /**
     * How early a card may be renewed.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ NINETY DAYS, MATCHING EVERY OTHER HORIZON IN THE SYSTEM — the
     * dashboard's "à échéance", the institution's banner, and
     * InstitutionalRenewalJob. Three numbers meaning "soon" would eventually
     * mean three different things.
     *
     * A card outside the window is neither lapsed nor lapsing, and renewing
     * it would forfeit the months remaining: the successor starts its own
     * term, and the predecessor is retired the same instant.
     * ───────────────────────────────────────────────────────────────────
     */
    private static final int RENEWAL_WINDOW_DAYS = 90;

    private final HonourCardRepository repository;
    private final PrintRunRepository runRepository;
    private final CardSigningService signingService;
    private final PhotoStorageService photoStorage;

    public HonourCardService(HonourCardRepository repository,
                             PrintRunRepository runRepository,
                             CardSigningService signingService,
                             PhotoStorageService photoStorage) {
        this.repository = repository;
        this.runRepository = runRepository;
        this.signingService = signingService;
        this.photoStorage = photoStorage;
    }

    /** What the Ministry fills in. */
    public record GrantRequest(
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            Long categoryId,
            Long specialisationId,
            String institution,
            LocalDate expiresAt,
            String grantReason
    ) {}

    @Transactional
    public HonourCard grant(GrantRequest request, Long actorId) {
        LocalDate issuedAt = LocalDate.now();

        /*
         * ⚠️ EVERYTHING CHECKED BEFORE A NUMBER IS TAKEN.
         *
         * The same rule as CardService.issue: a sequence value consumed by a
         * failed grant is a permanent gap in the register — small, but a
         * register with unexplained holes invites the question of what was
         * removed.
         */
        if (request.expiresAt() == null || !request.expiresAt().isAfter(issuedAt)) {
            throw new HonourCardException("validation.expiryMustBeFuture");
        }
        if (request.grantReason() == null || request.grantReason().isBlank()) {
            throw new HonourCardException("validation.grantReasonRequired");
        }
        if (request.identityNumber() == null || request.identityNumber().isBlank()) {
            // Not bureaucracy: the signature is computed over it.
            throw new HonourCardException("validation.identityRequired");
        }

        String cardNumber = nextCardNumber(issuedAt);

        HonourCard card = repository.save(HonourCard.builder()
                .cardNumber(cardNumber)
                .fullName(request.fullName().trim())
                .identityNumber(request.identityNumber().replaceAll("\\s", ""))
                .birthdate(request.birthdate())
                .birthplace(request.birthplace())
                .categoryId(request.categoryId())
                .specialisationId(request.specialisationId())
                .institution(request.institution())
                .issuedAt(issuedAt)
                .expiresAt(request.expiresAt())
                .status(CardStatus.VALID)
                .verificationToken(newVerificationToken())
                .grantedBy(actorId)
                .grantReason(request.grantReason().trim())
                .updatedAt(OffsetDateTime.now())
                .build());

        sign(card);
        repository.save(card);

        log.info("HONOUR_CARD_GRANTED number={} holder={} by={} expires={}",
                cardNumber, card.getFullName(), actorId, card.getExpiresAt());
        return card;
    }

    /**
     * Edit the holder's details.
     *
     * ⚠️ REFUSED ONCE THE CARD HAS BEEN PRODUCED, and this is the important
     * rule in the service.
     *
     * The signature covers the name, the identity number and the dates. Edit
     * them on a card whose plastic already exists, and the record and the
     * object disagree — the signature then verifies the NEW name against a
     * card showing the OLD one, so a scan reports a mismatch on a credential
     * the Ministry itself issued.
     *
     * That failure appears at a checkpoint, months later, and looks exactly
     * like forgery.
     *
     * A correction after printing is therefore not an edit. It is a
     * revocation and a new grant — which is what revocation is for, and what
     * an administration does with a passport bearing a wrong name.
     */
    @Transactional
    public HonourCard update(Long id, GrantRequest request) {
        HonourCard card = find(id);

        if (runRepository.honourCardWasProduced(id)) {
            throw new HonourCardException("validation.honourCardAlreadyProduced");
        }
        /*
         * ⚠️ A REPLACED CARD IS CLOSED, EVEN IF IT WAS NEVER PRINTED.
         *
         * Its successor carries the holder's details, copied at renewal.
         * Changing the predecessor afterwards leaves two rows disagreeing
         * about the same person, and the register can no longer say which
         * name the distinction was granted under.
         *
         * ⚠️ THE PRODUCTION GUARD ABOVE DOES NOT COVER THIS. A card renewed
         * before it ever reached the printer has produced = false, so the
         * pencil stayed enabled on a card that is finished.
         */
        if (repository.existsByRenewedFromCardId(id)) {
            throw new HonourCardException("validation.honourCardReplaced");
        }

        if (request.expiresAt() == null || !request.expiresAt().isAfter(LocalDate.now())) {
            throw new HonourCardException("validation.expiryMustBeFuture");
        }

        card.setFullName(request.fullName().trim());
        card.setIdentityNumber(request.identityNumber().replaceAll("\\s", ""));
        card.setBirthdate(request.birthdate());
        card.setBirthplace(request.birthplace());
        card.setCategoryId(request.categoryId());
        card.setSpecialisationId(request.specialisationId());
        card.setInstitution(request.institution());
        card.setExpiresAt(request.expiresAt());
        card.setUpdatedAt(OffsetDateTime.now());

        // ⚠️ RE-SIGNED. The signature covers what just changed; leaving the
        // old one would make every future scan report a mismatch.
        sign(card);
        repository.save(card);

        log.info("HONOUR_CARD_UPDATED number={}", card.getCardNumber());
        return card;
    }

    /**
     * Attach or replace the photograph.
     *
     * ⚠️ SEPARATE FROM THE GRANT: an upload is multipart and the grant is
     * JSON — and a photograph that fails to store must not roll back a card
     * number already taken.
     *
     * ⚠️ AND REFUSED ONCE PRODUCED, for the same reason as an edit and more
     * visibly. The photograph is not part of the signature, but it is the
     * FACE on the card: replacing it afterwards would leave the record
     * describing someone the plastic does not show.
     */
    @Transactional
    public HonourCard attachPhoto(Long id, MultipartFile file) {
        HonourCard card = find(id);

        if (runRepository.honourCardWasProduced(id)) {
            throw new HonourCardException("validation.honourCardAlreadyProduced");
        }
        /*
         * ⚠️ A REPLACED CARD IS CLOSED, EVEN IF IT WAS NEVER PRINTED.
         *
         * Its successor carries the holder's details, copied at renewal.
         * Changing the predecessor afterwards leaves two rows disagreeing
         * about the same person, and the register can no longer say which
         * name the distinction was granted under.
         *
         * ⚠️ THE PRODUCTION GUARD ABOVE DOES NOT COVER THIS. A card renewed
         * before it ever reached the printer has produced = false, so the
         * pencil stayed enabled on a card that is finished.
         */
        if (repository.existsByRenewedFromCardId(id)) {
            throw new HonourCardException("validation.honourCardReplaced");
        }

        card.setPhotoPath(photoStorage.storeForHonourCard(
                file, card.getId(), card.getPhotoPath()));
        card.setUpdatedAt(OffsetDateTime.now());
        repository.save(card);

        log.info("HONOUR_CARD_PHOTO number={}", card.getCardNumber());
        return card;
    }

    /**
     * Suspend, revoke, or restore.
     *
     * ⚠️ NOT restricted by production, unlike an edit — and that asymmetry is
     * the point. An honour card gets lost like any other, and the one already
     * in circulation is precisely the one that must be stoppable. Without
     * this, whoever finds it holds a credential that scans green for ever.
     */
    @Transactional
    public HonourCard changeStatus(Long id, CardStatus status, String reason, Long actorId) {
        HonourCard card = find(id);

        if (status != CardStatus.VALID && (reason == null || reason.isBlank())) {
            throw new HonourCardException("validation.statusReasonRequired");
        }

        card.setStatus(status);
        // Kept on restoration too: the record of why it was withdrawn does not
        // stop being true when it is given back.
        card.setStatusReason(reason);
        card.setStatusChangedAt(OffsetDateTime.now());
        card.setStatusChangedBy(actorId);
        card.setUpdatedAt(OffsetDateTime.now());
        repository.save(card);

        log.info("HONOUR_CARD_STATUS number={} status={} by={}",
                card.getCardNumber(), status, actorId);
        return card;
    }

    /**
     * Renew an honour card.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ ONE CLICK, AND THAT IS NOT A SHORTCUT.
     *
     * A press card is renewed through a dossier, because a journalist's
     * activity may have changed. An institutional card through a re-filing,
     * because employment may have. An honour card has nothing of the kind to
     * re-establish: the Ministry decides whether the distinction continues,
     * and that decision is the whole of the act.
     *
     * ⚠️ THE HOLDER'S DETAILS ARE COPIED, NOT RE-ENTERED.
     *
     * The identity was verified at the first grant and does not change. A
     * form asking for it again would invite a typo — and the signature is
     * computed over the identity number, so a typo is a card that scans as
     * forged.
     *
     * ⚠️ THE PREDECESSOR IS RETIRED BEFORE THE NEW CARD IS SAVED.
     *
     * Two live honour cards for one person, even for an instant, would both
     * scan green. Retiring first means they never coexist — and if an index
     * on the identity is ever added, this order is what keeps renewals
     * working.
     * ───────────────────────────────────────────────────────────────────
     */
    @Transactional
    public HonourCard renew(Long previousId, LocalDate expiresAt,
                            String grantReason, Long actorId) {
        HonourCard previous = find(previousId);
        LocalDate issuedAt = LocalDate.now();

        /* ── every precondition BEFORE a number is taken ── */

        if (repository.existsByRenewedFromCardId(previousId)) {
            throw new HonourCardException("validation.honourAlreadyRenewed");
        }
        /*
         * ⚠️ A REVOKED CARD IS NOT RENEWED.
         *
         * Revocation withdraws a distinction for a reason. Renewing it would
         * quietly reinstate what somebody decided to remove — and a fresh
         * grant, with a fresh reason, is the honest way to do that.
         *
         * A SUSPENDED card may be renewed: suspension is a pause, not a
         * verdict, and it is often a lost card awaiting replacement.
         */
        if (previous.getStatus() == CardStatus.REVOKED) {
            throw new HonourCardException("validation.honourRevokedNotRenewable");
        }
        /*
         * ⚠️ NOT BEFORE THE WINDOW — EXCEPT WHEN SUSPENDED.
         *
         * A suspended card is usually a lost one awaiting replacement, and
         * that is precisely a renewal. Suspension carries no date, so tying
         * it to the expiry would refuse the case the exception exists for.
         *
         * Everything else waits: a card with a year left is neither lapsed
         * nor lapsing, and renewing it early would throw away that year.
         */
        if (previous.getStatus() != CardStatus.SUSPENDED
                && previous.getExpiresAt() != null
                && previous.getExpiresAt().isAfter(issuedAt.plusDays(RENEWAL_WINDOW_DAYS))) {
            throw new HonourCardException("validation.honourNotYetRenewable");
        }
        if (expiresAt == null || !expiresAt.isAfter(issuedAt)) {
            throw new HonourCardException("validation.expiryMustBeFuture");
        }

        String reason = grantReason == null || grantReason.isBlank()
                ? previous.getGrantReason()
                : grantReason.trim();

        String cardNumber = nextCardNumber(issuedAt);

        // ⚠️ Retired FIRST — see the javadoc.
        previous.setStatus(CardStatus.REVOKED);
        previous.setStatusReason("Carte renouvelée. Remplacée par la carte n° " + cardNumber + ".");
        previous.setStatusChangedAt(OffsetDateTime.now());
        previous.setStatusChangedBy(actorId);
        previous.setUpdatedAt(OffsetDateTime.now());
        repository.save(previous);

        HonourCard card = repository.save(HonourCard.builder()
                .cardNumber(cardNumber)
                .fullName(previous.getFullName())
                .identityNumber(previous.getIdentityNumber())
                .birthdate(previous.getBirthdate())
                .birthplace(previous.getBirthplace())
                .categoryId(previous.getCategoryId())
                .specialisationId(previous.getSpecialisationId())
                .institution(previous.getInstitution())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .status(CardStatus.VALID)
                .verificationToken(newVerificationToken())
                .grantedBy(actorId)
                .grantReason(reason)
                .renewedFromCardId(previous.getId())
                .updatedAt(OffsetDateTime.now())
                .build());

        /*
         * ⚠️ THE PHOTOGRAPH IS COPIED, and the screen says so.
         *
         * Forcing a new one would burden a distinguished figure the Ministry
         * chose to honour. Carrying it over silently would put a four-year-old
         * face on a four-year card. So it is carried over and the dialog says
         * "reprise de la carte précédente — remplacez-la si elle date".
         */
        card.setPhotoPath(photoStorage.copyForHonourCard(previous.getPhotoPath(), card.getId()));

        sign(card);
        repository.save(card);

        log.info("HONOUR_CARD_RENEWED number={} previous={} holder={} by={} expires={}",
                cardNumber, previous.getCardNumber(), card.getFullName(), actorId, expiresAt);
        return card;
    }

    /* ══ reads ══ */

    @Transactional(readOnly = true)
    public List<HonourCard> all() {
        return repository.findAllByOrderByIssuedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<HonourCard> producible() {
        return repository.findProducible(CardStatus.VALID);
    }

    @Transactional(readOnly = true)
    public HonourCard find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new HonourCardNotFoundException(id));
    }

    /**
     * Why this card can no longer be edited, or null if it can.
     *
     * ⚠️ DECIDED HERE, so the screen never works it out.
     *
     * The same principle as the submission gate and the objection eligibility
     * object: the server decides and says why. A greyed-out button with no
     * explanation is a question nobody can answer — and a second
     * implementation of the rule in the UI is two rules that will eventually
     * disagree.
     */
    /**
     * Why this card cannot be renewed today, or null if it can.
     *
     * ⚠️ DECIDED HERE, so the screen never works it out — the same principle
     * as cannotEditReasonFr. A button offered and then refused is worse than
     * a button that is not offered, and a second copy of this rule in the UI
     * is two rules that will disagree the day the window changes.
     */
    public static String cannotRenewReasonFr(HonourCard card, boolean alreadyRenewed) {
        if (alreadyRenewed) {
            return "Cette carte a déjà été renouvelée.";
        }
        if (card.getStatus() == CardStatus.REVOKED) {
            return "Une carte retirée ne se renouvelle pas : procédez à un "
                    + "nouvel octroi, avec son motif.";
        }
        if (card.getStatus() != CardStatus.SUSPENDED
                && card.getExpiresAt() != null
                && card.getExpiresAt().isAfter(LocalDate.now().plusDays(RENEWAL_WINDOW_DAYS))) {
            return "Le renouvellement s'ouvre %d jours avant l'échéance, soit le %s."
                    .formatted(RENEWAL_WINDOW_DAYS,
                            card.getExpiresAt().minusDays(RENEWAL_WINDOW_DAYS));
        }
        return null;
    }

    @Transactional(readOnly = true)
    public boolean wasProduced(Long id) {
        return runRepository.honourCardWasProduced(id);
    }

    /* ══ internals ══ */

    /**
     * ⚠️ THE SAME CANONICAL FORM AS AN ORDINARY CARD, with the same key.
     *
     * A scan must not be able to tell that this card skipped the examination —
     * that distinction belongs in the register and on the B in its number, not
     * in whether the signature checks out.
     */
    private void sign(HonourCard card) {
        String canonical = CardSigningService.canonicalForm(
                card.getCardNumber(),
                card.getIdentityNumber(),
                card.getFullName(),
                card.getIssuedAt().toString(),
                card.getExpiresAt().toString());

        card.setSignature(signingService.sign(canonical));
        card.setSignatureKeyId(signingService.currentKeyId());
    }

    /** B - 0001 / 26 — series letter, four-digit sequence, two-digit year. */
    private String nextCardNumber(LocalDate issuedAt) {
        Long next = repository.nextCardNumber();
        return "%s - %04d / %02d".formatted(SERIES, next, issuedAt.getYear() % 100);
    }

    /** 128 bits of randomness — guessing one is infeasible. */
    private String newVerificationToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}