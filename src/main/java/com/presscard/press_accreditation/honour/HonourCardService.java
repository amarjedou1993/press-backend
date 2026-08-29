//package com.presscard.press_accreditation.honour;
//
//import com.presscard.press_accreditation.card.CardSigningService;
//import com.presscard.press_accreditation.card.CardStatus;
//import com.presscard.press_accreditation.config.AppProperties;
//import com.presscard.press_accreditation.storage.PhotoStorageService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.security.SecureRandom;
//import java.time.LocalDate;
//import java.time.OffsetDateTime;
//import java.util.Base64;
//import java.util.List;
//
///**
// * Granting, and withdrawing, cards that no commission examined.
// *
// * ───────────────────────────────────────────────────────────────────────
// * ⚠️ THE SIGNATURE IS THE POINT OF THIS SERVICE.
// *
// * Everything else here is a form being saved. The signature is what makes the
// * card verifiable — computed over the SAME canonical form an ordinary card
// * uses, with the same key, so a scan cannot tell that this one skipped the
// * examination.
// *
// * Without it, `signatureValid` comes back false and the verification page
// * reports the Ministry's own card as unverifiable. Not "unknown". Suspect.
// * ───────────────────────────────────────────────────────────────────────
// */
//@Service
//public class HonourCardService {
//
//    private static final Logger log = LoggerFactory.getLogger("HONOUR_CARD_AUDIT");
//    private static final SecureRandom RANDOM = new SecureRandom();
//
//    /** The series letter. Fixed, unlike the A series' configurable one. */
//    private static final String SERIES = "B";
//
//    private final HonourCardRepository repository;
//    private final CardSigningService signingService;
//    private final PhotoStorageService photoStorage;
//    private final AppProperties props;
//
//    public HonourCardService(HonourCardRepository repository,
//                             CardSigningService signingService,
//                             PhotoStorageService photoStorage,
//                             AppProperties props) {
//        this.repository = repository;
//        this.signingService = signingService;
//        this.photoStorage = photoStorage;
//        this.props = props;
//    }
//
//    /** What the Ministry fills in. */
//    public record GrantRequest(
//            String fullName,
//            String identityNumber,
//            LocalDate birthdate,
//            String birthplace,
//            Long categoryId,
//            Long specialisationId,
//            String institution,
//            LocalDate expiresAt,
//            String grantReason
//    ) {}
//
//    @Transactional
//    public HonourCard grant(GrantRequest request, Long actorId) {
//        LocalDate issuedAt = LocalDate.now();
//
//        /*
//         * ⚠️ EVERYTHING CHECKED BEFORE A NUMBER IS TAKEN.
//         *
//         * The same rule as CardService.issue: a sequence value consumed by a
//         * failed grant is a permanent gap in the register — small, but a
//         * register with unexplained holes invites the question of what was
//         * removed.
//         */
//        if (request.expiresAt() == null || !request.expiresAt().isAfter(issuedAt)) {
//            throw new HonourCardException("validation.expiryMustBeFuture");
//        }
//        if (request.grantReason() == null || request.grantReason().isBlank()) {
//            throw new HonourCardException("validation.grantReasonRequired");
//        }
//        if (request.identityNumber() == null || request.identityNumber().isBlank()) {
//            // Not bureaucracy: the signature is computed over it.
//            throw new HonourCardException("validation.identityRequired");
//        }
//
//        String cardNumber = nextCardNumber(issuedAt);
//
//        HonourCard card = repository.save(HonourCard.builder()
//                .cardNumber(cardNumber)
//                .fullName(request.fullName().trim())
//                .identityNumber(request.identityNumber().replaceAll("\\s", ""))
//                .birthdate(request.birthdate())
//                .birthplace(request.birthplace())
//                .categoryId(request.categoryId())
//                .specialisationId(request.specialisationId())
//                .institution(request.institution())
//                .issuedAt(issuedAt)
//                .expiresAt(request.expiresAt())
//                .status(CardStatus.VALID)
//                .verificationToken(newVerificationToken())
//                .grantedBy(actorId)
//                .grantReason(request.grantReason().trim())
//                .updatedAt(OffsetDateTime.now())
//                .build());
//
//        sign(card);
//        repository.save(card);
//
//        log.info("HONOUR_CARD_GRANTED number={} holder={} by={} expires={}",
//                cardNumber, card.getFullName(), actorId, card.getExpiresAt());
//        return card;
//    }
//
//    /**
//     * Attach the photograph.
//     *
//     * ⚠️ SEPARATE FROM THE GRANT, because a file upload is multipart and the
//     * grant is JSON — and because a photograph that fails to store must not
//     * roll back a card number that has already been taken.
//     */
//    @Transactional
//    public HonourCard attachPhoto(Long id, MultipartFile file) {
//        HonourCard card = find(id);
////        card.setPhotoPath(photoStorage.storeForHonourCard(file, card.getId()));
//        card.setPhotoPath(photoStorage.storeForHonourCard(
//                file, card.getId(), card.getPhotoPath()));
//        card.setUpdatedAt(OffsetDateTime.now());
//        repository.save(card);
//
//        log.info("HONOUR_CARD_PHOTO number={}", card.getCardNumber());
//        return card;
//    }
//
//    /**
//     * Edit the holder's details.
//     *
//     * ⚠️ RE-SIGNS. The signature covers the name, the identity number and the
//     * dates — change any of them without re-signing and every future scan
//     * reports the card as unverifiable.
//     *
//     * ⚠️ AND THE CARD NUMBER NEVER CHANGES. It is on a printed object.
//     */
//    @Transactional
//    public HonourCard update(Long id, GrantRequest request) {
//        HonourCard card = find(id);
//
//        if (request.expiresAt() == null || !request.expiresAt().isAfter(LocalDate.now())) {
//            throw new HonourCardException("validation.expiryMustBeFuture");
//        }
//
//        card.setFullName(request.fullName().trim());
//        card.setIdentityNumber(request.identityNumber().replaceAll("\\s", ""));
//        card.setBirthdate(request.birthdate());
//        card.setBirthplace(request.birthplace());
//        card.setCategoryId(request.categoryId());
//        card.setSpecialisationId(request.specialisationId());
//        card.setInstitution(request.institution());
//        card.setExpiresAt(request.expiresAt());
//        card.setUpdatedAt(OffsetDateTime.now());
//
//        sign(card);
//        repository.save(card);
//
//        log.info("HONOUR_CARD_UPDATED number={} by=edit", card.getCardNumber());
//        return card;
//    }
//
//    /**
//     * Suspend, revoke, or restore.
//     *
//     * ⚠️ THE REASON A LIFECYCLE EXISTS HERE AT ALL: an honour card gets lost
//     * like any other. Without this, whoever finds one holds a credential that
//     * scans green for ever.
//     */
//    @Transactional
//    public HonourCard changeStatus(Long id, CardStatus status, String reason, Long actorId) {
//        HonourCard card = find(id);
//
//        if (status != CardStatus.VALID && (reason == null || reason.isBlank())) {
//            throw new HonourCardException("validation.statusReasonRequired");
//        }
//
//        card.setStatus(status);
//        // Kept on restoration too: the record of why it was withdrawn does not
//        // stop being true when it is given back.
//        card.setStatusReason(reason);
//        card.setStatusChangedAt(OffsetDateTime.now());
//        card.setStatusChangedBy(actorId);
//        card.setUpdatedAt(OffsetDateTime.now());
//        repository.save(card);
//
//        log.info("HONOUR_CARD_STATUS number={} status={} by={}",
//                card.getCardNumber(), status, actorId);
//        return card;
//    }
//
//    @Transactional(readOnly = true)
//    public List<HonourCard> all() {
//        return repository.findAllByOrderByIssuedAtDesc();
//    }
//
//    @Transactional(readOnly = true)
//    public List<HonourCard> producible() {
//        return repository.findProducible(CardStatus.VALID);
//    }
//
//    @Transactional(readOnly = true)
//    public HonourCard find(Long id) {
//        return repository.findById(id)
//                .orElseThrow(() -> new HonourCardNotFoundException(id));
//    }
//
//    /* ══ internals ══ */
//
//    /**
//     * ⚠️ THE SAME CANONICAL FORM AS AN ORDINARY CARD, with the same key.
//     *
//     * A scan must not be able to tell that this card skipped the examination —
//     * that distinction belongs in the register and on the B in its number, not
//     * in whether the signature checks out.
//     */
//    private void sign(HonourCard card) {
//        String canonical = CardSigningService.canonicalForm(
//                card.getCardNumber(),
//                card.getIdentityNumber(),
//                card.getFullName(),
//                card.getIssuedAt().toString(),
//                card.getExpiresAt().toString());
//
//        card.setSignature(signingService.sign(canonical));
//        card.setSignatureKeyId(signingService.currentKeyId());
//    }
//
//    /** B - 0001 / 26 — series letter, four-digit sequence, two-digit year. */
//    private String nextCardNumber(LocalDate issuedAt) {
//        Long next = repository.nextCardNumber();
//        return "%s - %04d / %02d".formatted(SERIES, next, issuedAt.getYear() % 100);
//    }
//
//    /** 128 bits of randomness — guessing one is infeasible. */
//    private String newVerificationToken() {
//        byte[] bytes = new byte[16];
//        RANDOM.nextBytes(bytes);
//        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
//    }
//}


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