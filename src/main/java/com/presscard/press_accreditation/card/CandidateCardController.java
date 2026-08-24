package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/me")
@PreAuthorize("hasRole('CANDIDATE')")
public class CandidateCardController {

    /** The holder's own card, as they may see it. */
    public record MyCardResponse(
            String cardNumber,
            String categoryLabelFr,
            String categoryLabelAr,
            String specialisationFr,
            String specialisationAr,
            String institution,
            LocalDate issuedAt,
            LocalDate expiresAt,
            /** The CONSTANT — what a bilingual screen reads. */
            String status,
            String statusLabelFr,
            String statusLabelAr,
            /**
             * When the status last changed.
             *
             * ⚠️ NOT the same as issuedAt, and the difference matters: a
             * withdrawal notice dated the day the card was printed reads as
             * though the Ministry revoked it on issue.
             */
            OffsetDateTime statusChangedAt,
            boolean expired,
            /** False when suspended, revoked or lapsed. */
            boolean usable,
            /** Set when the card is not in force — the holder is owed the reason. */
            String statusReason
    ) {}

    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final UserRepository userRepository;

    public CandidateCardController(CardRepository cardRepository,
                                   ApplicationRepository applicationRepository,
                                   PressCategoryRepository categoryRepository,
                                   UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    /**
     * 204 when the caller holds no card — not 404.
     *
     * Having no card yet is a normal state for a candidate, not an error, and
     * the dashboard should not have to treat it as one.
     */
    @GetMapping("/card")
    @Transactional(readOnly = true)
    public ResponseEntity<MyCardResponse> myCard(Principal principal) {
        Long userId = userRepository.findByEmail(principal.getName())
                .orElseThrow().getId();

        // The most recent application that produced a card.
        Card card = applicationRepository
                .findByCandidateIdOrderByCreatedAtDesc(userId).stream()
                .map(Application::getId)
                .map(id -> cardRepository.findByApplicationId(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (card == null) {
            return ResponseEntity.noContent().build();
        }

        Application application = applicationRepository
                .findById(card.getApplicationId()).orElseThrow();
        PressCategory category = categoryRepository
                .findById(application.getCategoryId()).orElse(null);

        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        return ResponseEntity.ok(new MyCardResponse(
                card.getCardNumber(),
                category == null ? null : category.getLabelFr(),
                category == null ? null : category.getLabelAr(),
                card.getSpecialisationFr(),
                card.getSpecialisationAr(),
                card.getInstitution(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                lapsed ? "EXPIRED" : card.getStatus().name(),
                lapsed ? "Expirée" : card.getStatus().labelFr(),
                lapsed ? "منتهية الصلاحية" : card.getStatus().labelAr(),
                // ⚠️ A lapsed card has no status CHANGE — nobody acted on it,
                // it simply reached its date. Sending the last administrative
                // change would date the expiry to whenever the card was
                // issued, which is a different and untrue statement.
                lapsed ? null : card.getStatusChangedAt(),
                expired,
                card.isUsable(),
                // The holder is entitled to know why their card stopped
                // working — they will find out at a checkpoint otherwise.
                card.getStatus() == CardStatus.VALID ? null : card.getStatusReason()));
    }
}