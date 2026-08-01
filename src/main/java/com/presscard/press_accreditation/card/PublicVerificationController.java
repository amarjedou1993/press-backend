package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
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
 */
@RestController
@RequestMapping("/api/public/verify")
public class PublicVerificationController {

    private static final Logger log = LoggerFactory.getLogger("CARD_VERIFICATION");

    private final CardService cardService;
    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final com.presscard.press_accreditation.storage.PhotoStorageService photoStorage;

    public PublicVerificationController(
            CardService cardService,
            CardRepository cardRepository,
            ApplicationRepository applicationRepository,
            PressCategoryRepository categoryRepository,
            com.presscard.press_accreditation.storage.PhotoStorageService photoStorage) {
        this.cardService = cardService;
        this.cardRepository = cardRepository;
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

        // The category is the one detail worth adding: "journaliste" and
        // "photographe de presse" carry different access rights at an event.
        String categoryLabel = cardRepository.findByCardNumber(result.cardNumber())
                .flatMap(c -> applicationRepository.findById(c.getApplicationId()))
                .map(Application::getCategoryId)
                .flatMap(categoryRepository::findById)
                .map(PressCategory::getLabelFr)
                .orElse(null);

        return new CardService.VerificationResult(
                result.found(), result.status(), result.statusLabelFr(), result.statusLabelAr(),
                result.usable(), result.cardNumber(), result.holderFullName(),
                categoryLabel, result.issuedAt(), result.expiresAt(),
                result.signatureValid(), result.statusNoteFr());
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
        Card card = cardRepository.findByVerificationToken(token).orElse(null);

//        if (card == null || !card.isUsable() || card.getPhotoPath() == null) {
//            return ResponseEntity.notFound().build();
//        }

        if (card == null || !card.getStatus().isInForce() || card.getPhotoPath() == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = photoStorage.resolve(card.getPhotoPath());
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            String contentType = Files.probeContentType(path);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            contentType != null ? contentType : "image/jpeg"))
                    // Personal data on a public endpoint: never cached by a proxy.
                    .cacheControl(CacheControl.noStore().cachePrivate())
                    .body(Files.readAllBytes(path));

        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
