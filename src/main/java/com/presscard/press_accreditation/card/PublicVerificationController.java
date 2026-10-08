package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.honour.HonourCard;
import com.presscard.press_accreditation.honour.HonourCardRepository;
import com.presscard.press_accreditation.institutional.InstitutionalCard;
import com.presscard.press_accreditation.institutional.InstitutionalCardRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a scanned QR resolves to. PUBLIC — no authentication, by design: a
 * police officer or an event organiser checking a card at a door has no
 * account and never will.
 *
 * WHAT IT DISCLOSES, AND WHY THAT LIST IS SHORT.
 *
 * Whoever scans is holding the card, so they can already read the name,
 * number and dates printed on it. The endpoint adds exactly two things they
 * cannot get from the plastic: the LIVE STATUS, and the PHOTOGRAPH — which is
 * what lets them confirm the person in front of them is the holder. Without
 * the photograph, verification proves only that a card exists.
 *
 * It discloses nothing else. No e-mail, no telephone, no NNI, no dossier
 * history — none of which a verifier needs and all of which would turn a
 * scan into a personal-data leak.
 *
 * ENUMERATION IS THE OTHER HALF. The token is 128 random bits, so the only
 * way to reach a record is to hold the card it is printed on.
 *
 * ⚠️ THREE SERIES, ONE ANSWER. A (commission), B (honour) and C
 * (institutional) cards carry the same kind of token and must read the same
 * way at a checkpoint: status, name, category, face. Every lookup below falls
 * through the three in that order.
 */
@RestController
@RequestMapping("/api/public/verify")
public class PublicVerificationController {

    private static final Logger log = LoggerFactory.getLogger("CARD_VERIFICATION");

    private final CardService cardService;
    private final CardRepository cardRepository;
    private final HonourCardRepository honourCardRepository;
    private final InstitutionalCardRepository institutionalCardRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final PhotoStorageService photoStorage;

    public PublicVerificationController(
            CardService cardService,
            CardRepository cardRepository,
            HonourCardRepository honourCardRepository,
            InstitutionalCardRepository institutionalCardRepository,
            ApplicationRepository applicationRepository,
            PressCategoryRepository categoryRepository,
            PhotoStorageService photoStorage) {
        this.cardService = cardService;
        this.cardRepository = cardRepository;
        this.honourCardRepository = honourCardRepository;
        this.institutionalCardRepository = institutionalCardRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.photoStorage = photoStorage;
    }

    /** Resolve a scanned token. */
    @GetMapping("/{token}")
    public CardService.VerificationResult verify(@PathVariable String token) {
        CardService.VerificationResult result = cardService.verify(token);

        // Logged without the token: the log must not become a way to replay
        // lookups against journalists' records.
        log.info("CARD_VERIFIED found={} status={} usable={}",
                result.found(), result.status(), result.usable());

        if (!result.found()) {
            return result;
        }

        /*
         * ⚠️ THREE PATHS TO THE SAME LABEL.
         *
         * An ordinary card takes its category from its dossier; an honour card
         * and an institutional card carry it directly. The answer is the same
         * either way — "journaliste" and "photographe de presse" open different
         * accesses at an event, and an agent has no need to know which series
         * they are holding.
         */
        PressCategory category = categoryOf(token, result.cardNumber());

        return new CardService.VerificationResult(
                result.found(), result.status(), result.statusLabelFr(), result.statusLabelAr(),
                result.usable(), result.cardNumber(), result.holderFullName(),
                category == null ? null : category.getLabelFr(),
                category == null ? null : category.getLabelAr(),
                result.issuedAt(), result.expiresAt(),
                result.signatureValid(),
                result.statusNoteFr(), result.statusNoteAr());
    }

    /**
     * The holder's photograph, by TOKEN.
     *
     * The single most useful thing a verifier gets: it lets them confirm the
     * person in front of them. Served only for a card that is actually in
     * force — a revoked card discloses no photograph, because there is nobody
     * to confirm.
     */
    @GetMapping("/{token}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable String token) {
        String path = null;
        CardStatus status = null;

        /*
         * ⚠️ WITHOUT THE FALLBACKS, B AND C CARDS SCAN WITHOUT A FACE.
         *
         * And a verification without a face verifies nothing: it confirms that
         * a number exists, not that the person handing over the card is the
         * one it was issued to. That is precisely the check an agent performs.
         */
        Card card = cardRepository.findByVerificationToken(token).orElse(null);
        if (card != null) {
            path = card.getPhotoPath();
            status = card.getStatus();
        } else {
            HonourCard honour = honourCardRepository
                    .findByVerificationToken(token).orElse(null);
            if (honour != null) {
                path = honour.getPhotoPath();
                status = honour.getStatus();
            } else {
                InstitutionalCard institutional = institutionalCardRepository
                        .findByVerificationToken(token)
                        .filter(InstitutionalCard::isGranted)
                        .orElse(null);
                if (institutional != null) {
                    path = institutional.getPhotoPath();
                    status = institutional.getStatus();
                }
            }
        }

        if (path == null || status == null || !status.isInForce()) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path file = photoStorage.resolve(path);
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            String contentType = Files.probeContentType(file);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            contentType != null ? contentType : "image/jpeg"))
                    // Personal data on a public endpoint: never cached by a proxy.
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .body(Files.readAllBytes(file));

        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    /* ══ internals ════════════════════════════════════════════ */

    /** The category, whichever series the token belongs to. */
    private PressCategory categoryOf(String token, String cardNumber) {
        PressCategory fromDossier = cardRepository.findByCardNumber(cardNumber)
                .flatMap(c -> applicationRepository.findById(c.getApplicationId()))
                .map(Application::getCategoryId)
                .flatMap(categoryRepository::findById)
                .orElse(null);
        if (fromDossier != null) {
            return fromDossier;
        }

        PressCategory fromHonour = honourCardRepository.findByVerificationToken(token)
                .map(HonourCard::getCategoryId)
                .flatMap(categoryRepository::findById)
                .orElse(null);
        if (fromHonour != null) {
            return fromHonour;
        }

        return institutionalCardRepository.findByVerificationToken(token)
                .map(InstitutionalCard::getCategoryId)
                .flatMap(categoryRepository::findById)
                .orElse(null);
    }
}