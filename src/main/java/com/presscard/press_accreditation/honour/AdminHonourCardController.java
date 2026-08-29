package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.card.PrintRunRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Honour cards, as the Ministry manages them.
 *
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**).
 *
 * ⚠️ THE WHOLE LIFECYCLE IS HERE, unlike an ordinary card whose issuance is
 * the end of a workflow beginning with a candidacy. There is no workflow: an
 * administrator fills a form, and the card exists.
 *
 * Which is exactly why grant_reason is mandatory and recorded. This card
 * bypasses the examination every other card requires, and the register must
 * say on whose authority and why.
 */
@RestController
@RequestMapping("/api/admin/honour-cards")
public class AdminHonourCardController {

    /* ── contracts ── */

    public record GrantBody(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Size(max = 40) String identityNumber,
            LocalDate birthdate,
            @Size(max = 200) String birthplace,
            Long categoryId,
            Long specialisationId,
            @Size(max = 200) String institution,
            @NotNull LocalDate expiresAt,
            @NotBlank String grantReason
    ) {}

    public record StatusBody(
            @NotNull CardStatus status,
            String reason
    ) {}

    public record HonourCardResponse(
            Long id,
            String cardNumber,
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            Long categoryId,
            String categoryLabelFr,
            Long specialisationId,
            String specialisationLabelFr,
            String institution,
            boolean hasPhoto,
            LocalDate issuedAt,
            LocalDate expiresAt,
            String status,
            String statusLabelFr,
            String statusReason,
            OffsetDateTime statusChangedAt,
            boolean expired,
            String grantedByName,
            String grantReason,

            /**
             * ⚠️ Whether this card's details may still be edited, and why not.
             *
             * The same principle as the submission gate and the objection
             * eligibility object: the SERVER decides and says why. A greyed
             * button with no explanation is a question nobody can answer, and
             * a second copy of the rule in the UI is two rules that will
             * eventually disagree about whether a credential is correct.
             */
            boolean produced,
            String cannotEditReasonFr
    ) {}

    private final HonourCardService service;
    private final PrintRunRepository runRepository;
    private final PressCategoryRepository categoryRepository;
    private final SpecialisationRepository specialisationRepository;
    private final UserRepository userRepository;

    public AdminHonourCardController(HonourCardService service,
                                     PrintRunRepository runRepository,
                                     PressCategoryRepository categoryRepository,
                                     SpecialisationRepository specialisationRepository,
                                     UserRepository userRepository) {
        this.service = service;
        this.runRepository = runRepository;
        this.categoryRepository = categoryRepository;
        this.specialisationRepository = specialisationRepository;
        this.userRepository = userRepository;
    }

    /* ══ reads ══ */

    @GetMapping
    @Transactional(readOnly = true)
    public List<HonourCardResponse> list() {
        List<HonourCard> cards = service.all();
        Map<Long, PressCategory> categories = categoryIndex();
        Map<Long, Specialisation> specialisations = specialisationIndex();

        /*
         * ⚠️ ONE QUERY FOR THE WHOLE LIST, not one per row.
         *
         * "Has this been produced?" decides whether each card may still be
         * edited, so every row needs it — and asked individually that is one
         * query per card, on a screen whose only job is to show them all.
         */
        Map<Long, Long> produced = producedIndex(
                cards.stream().map(HonourCard::getId).toList());

        return cards.stream()
                .map(card -> toResponse(card, categories, specialisations, produced))
                .toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public HonourCardResponse one(@PathVariable Long id) {
        HonourCard card = service.find(id);
        return toResponse(card, categoryIndex(), specialisationIndex(),
                producedIndex(List.of(id)));
    }

    /* ══ the grant ══ */

    @PostMapping
    public ResponseEntity<HonourCardResponse> grant(@Valid @RequestBody GrantBody body,
                                                    Principal principal) {
        HonourCard card = service.grant(toRequest(body), actorId(principal));
        // A card just granted has been produced zero times, by definition —
        // but the map is passed rather than assumed, so one code path builds
        // every response.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(card, categoryIndex(), specialisationIndex(),
                        producedIndex(List.of(card.getId()))));
    }

    @PutMapping("/{id}")
    public HonourCardResponse update(@PathVariable Long id,
                                     @Valid @RequestBody GrantBody body) {
        HonourCard card = service.update(id, toRequest(body));
        return toResponse(card, categoryIndex(), specialisationIndex(),
                producedIndex(List.of(id)));
    }

    /**
     * The photograph.
     *
     * ⚠️ Its own endpoint because an upload is multipart and the grant is
     * JSON — and because a photograph that fails to store must not roll back
     * a card number already taken.
     */
    @PostMapping("/{id}/photo")
    public HonourCardResponse photo(@PathVariable Long id,
                                    @RequestParam("file") MultipartFile file) {
        HonourCard card = service.attachPhoto(id, file);
        return toResponse(card, categoryIndex(), specialisationIndex(),
                producedIndex(List.of(id)));
    }

    /* ══ the lifecycle ══ */

    /**
     * Suspend, revoke, or restore.
     *
     * ⚠️ Available even on a produced card, unlike an edit. That asymmetry is
     * deliberate: the card already in circulation is precisely the one that
     * must be stoppable when it is lost.
     */
    @PatchMapping("/{id}/status")
    public HonourCardResponse status(@PathVariable Long id,
                                     @Valid @RequestBody StatusBody body,
                                     Principal principal) {
        HonourCard card = service.changeStatus(
                id, body.status(), body.reason(), actorId(principal));
        return toResponse(card, categoryIndex(), specialisationIndex(),
                producedIndex(List.of(id)));
    }

    /* ══ internals ══ */

    private HonourCardService.GrantRequest toRequest(GrantBody body) {
        return new HonourCardService.GrantRequest(
                body.fullName(), body.identityNumber(), body.birthdate(),
                body.birthplace(), body.categoryId(), body.specialisationId(),
                body.institution(), body.expiresAt(), body.grantReason());
    }

    private HonourCardResponse toResponse(HonourCard card,
                                          Map<Long, PressCategory> categories,
                                          Map<Long, Specialisation> specialisations,
                                          Map<Long, Long> producedCounts) {
        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        boolean produced = producedCounts.getOrDefault(card.getId(), 0L) > 0;

        PressCategory category = card.getCategoryId() == null ? null
                : categories.get(card.getCategoryId());
        Specialisation specialisation = card.getSpecialisationId() == null ? null
                : specialisations.get(card.getSpecialisationId());

        return new HonourCardResponse(
                card.getId(),
                card.getCardNumber(),
                card.getFullName(),
                card.getIdentityNumber(),
                card.getBirthdate(),
                card.getBirthplace(),
                card.getCategoryId(),
                category == null ? null : category.getLabelFr(),
                card.getSpecialisationId(),
                specialisation == null ? null : specialisation.getLabelFr(),
                card.getInstitution(),
                card.getPhotoPath() != null,
                card.getIssuedAt(),
                card.getExpiresAt(),
                lapsed ? "EXPIRED" : card.getStatus().name(),
                lapsed ? "Expirée" : card.getStatus().labelFr(),
                card.getStatusReason(),
                card.getStatusChangedAt(),
                expired,
                userRepository.findById(card.getGrantedBy())
                        .map(User::getFullName).orElse("—"),
                card.getGrantReason(),
                produced,
                produced
                        ? "Cette carte a déjà été produite : ses informations ne "
                          + "peuvent plus être modifiées. Pour corriger une erreur, "
                          + "retirez-la et accordez-en une nouvelle."
                        : null);
    }

    /**
     * How many times each of these cards has been produced.
     *
     * ⚠️ ONE QUERY, whatever the list's length. The alternative —
     * service.wasProduced(id) inside the mapping — is one query per row on a
     * screen that exists to show every row.
     *
     * The count itself is discarded here; only "more than zero" matters. It
     * is fetched as a count rather than a boolean because the same query
     * feeds the printer's list, where the number IS shown.
     */
    private Map<Long, Long> producedIndex(List<Long> honourCardIds) {
        if (honourCardIds.isEmpty()) {
            return Map.of();
        }
        return runRepository.countByHonourCardIds(honourCardIds).stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));
    }

    /** Read once per request — both catalogues are a handful of rows. */
    private Map<Long, PressCategory> categoryIndex() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));
    }

    private Map<Long, Specialisation> specialisationIndex() {
        return specialisationRepository.findAll().stream()
                .collect(Collectors.toMap(Specialisation::getId, Function.identity()));
    }

    private Long actorId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}