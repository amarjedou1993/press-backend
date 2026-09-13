package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Ministry's view: every institution's roll, and the power to grant.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ SEPARATE FROM THE INSTITUTION'S CONTROLLER, NOT A ROLE CHECK AWAY.
 *
 * They answer different questions. The institution asks "what have I filed";
 * the Ministry asks "what is waiting, from whom". One is scoped to a single
 * body and cannot name another; the other spans all of them and must.
 *
 * Folded into one class with role branches, the scoping would be a runtime
 * condition rather than a structural fact — and the condition that keeps one
 * institution from reading another's roll is not a condition worth writing
 * twice.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/admin/institutional")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminInstitutionalController {

    private final InstitutionalCardService service;
    private final InstitutionalCardRepository repository;
    private final InstitutionRepository institutionRepository;
    private final InstitutionalCardStatusHistoryRepository historyRepository;
    private final PressCategoryRepository categoryRepository;
    private final SpecialisationRepository specialisationRepository;
    private final UserRepository userRepository;

    public AdminInstitutionalController(InstitutionalCardService service,
                                        InstitutionalCardRepository repository,
                                        InstitutionRepository institutionRepository,
                                        InstitutionalCardStatusHistoryRepository historyRepository,
                                        PressCategoryRepository categoryRepository,
                                        SpecialisationRepository specialisationRepository,
                                        UserRepository userRepository) {
        this.service = service;
        this.repository = repository;
        this.institutionRepository = institutionRepository;
        this.historyRepository = historyRepository;
        this.categoryRepository = categoryRepository;
        this.specialisationRepository = specialisationRepository;
        this.userRepository = userRepository;
    }

    /* ══ responses ══ */

    public record CardResponse(
            Long id,
            Long institutionId,
            String institutionNameFr,
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            String jobTitle,
            String categoryLabelFr,
            String specialisationLabelFr,
            boolean hasPhoto,
            boolean granted,
            String cardNumber,
            LocalDate issuedAt,
            LocalDate expiresAt,
            String status,
            String statusLabelFr,
            boolean expired,
            /**
             * ⚠️ Whether this filing can be granted RIGHT NOW, and why not.
             *
             * The screen could work it out — a filing with no identity cannot
             * be granted — but then the rule would live in two places, and the
             * one that decides is here.
             */
            boolean grantable,
            String cannotGrantReasonFr,
            OffsetDateTime filedAt
    ) {}

    public record InstitutionResponse(
            Long id, String code, String nameFr, String nameAr,
            boolean active,
            /** How many of this body's filings await a grant. */
            long awaitingGrant
    ) {}

    /* ══ reading ══ */

    @GetMapping("/institutions")
    @Transactional(readOnly = true)
    public List<InstitutionResponse> institutions() {
        /*
         * ⚠️ ONE QUERY FOR THE COUNTS, not one per institution.
         *
         * There are two bodies today and there may be six. A count per row is
         * the shape that looks harmless at two and is a list of queries at
         * twenty — the same N+1 corrected across four controllers earlier.
         */
        Map<Long, Long> pending = repository.findAwaitingGrant().stream()
                .collect(Collectors.groupingBy(
                        InstitutionalCard::getInstitutionId, Collectors.counting()));

        return institutionRepository.findAll().stream()
                .map(i -> new InstitutionResponse(
                        i.getId(), i.getCode(), i.getNameFr(), i.getNameAr(),
                        i.isActive(), pending.getOrDefault(i.getId(), 0L)))
                .toList();
    }

    /** Everything awaiting a grant, across every institution, oldest first. */
    @GetMapping("/awaiting")
    @Transactional(readOnly = true)
    public List<CardResponse> awaiting() {
        return toResponses(repository.findAwaitingGrant());
    }

    /** One institution's whole roll — filed and granted alike. */
    @GetMapping("/institutions/{institutionId}/staff")
    @Transactional(readOnly = true)
    public List<CardResponse> staff(@PathVariable Long institutionId) {
        return toResponses(
                repository.findByInstitutionIdOrderByFiledAtDesc(institutionId));
    }

    @GetMapping("/{id}/history")
    @Transactional(readOnly = true)
    public List<InstitutionalCardStatusHistory> history(@PathVariable Long id) {
        return historyRepository.findByInstitutionalCardIdOrderByCreatedAtDesc(id);
    }

    /* ══ granting ══ */

    public record GrantBody(
            /**
             * ⚠️ THE MINISTRY'S TO SET, and required.
             *
             * The institution's spreadsheet carries no expiry column: a body
             * choosing how long its own staff's credentials last would be
             * choosing the term of a document it does not issue.
             */
            @NotNull(message = "validation.expiryMustBeFuture")
            LocalDate expiresAt
    ) {}

    @PostMapping("/{id}/grant")
    public CardResponse grant(@PathVariable Long id,
                              @Valid @RequestBody GrantBody body,
                              Principal principal) {
        InstitutionalCard card = service.grant(id, actorId(principal), body.expiresAt());
        return toResponses(List.of(card)).get(0);
    }

    public record GrantManyBody(
            @NotEmpty(message = "validation.selectionEmpty") List<Long> ids,
            @NotNull(message = "validation.expiryMustBeFuture") LocalDate expiresAt
    ) {}

    /**
     * Grant a whole batch.
     *
     * ⚠️ ONE EXPIRY FOR ALL OF THEM, deliberately.
     *
     * An institution's staff are accredited together and should lapse
     * together — the same reasoning that puts a card's expiry on its session
     * rather than on its issuance date. Granted one by one with different
     * dates, a renewal becomes a continuous chore instead of a cycle.
     */
    @PostMapping("/grant")
    public InstitutionalCardService.BatchResult grantMany(
            @Valid @RequestBody GrantManyBody body, Principal principal) {
        return service.grantMany(body.ids(), actorId(principal), body.expiresAt());
    }

    /* ══ internals ══ */

    /**
     * ⚠️ BATCHED, not one lookup per row.
     *
     * Four reference tables read once each, whatever the roll's size. The
     * same correction made to AdminCardController, ReviewerCardController and
     * PrinterController — written this way from the start here rather than
     * found later by a query-count test.
     */
    private List<CardResponse> toResponses(List<InstitutionalCard> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }

        Map<Long, Institution> institutions = institutionRepository.findAll().stream()
                .collect(Collectors.toMap(Institution::getId, Function.identity()));
        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));
        Map<Long, Specialisation> specialisations = specialisationRepository.findAll().stream()
                .collect(Collectors.toMap(Specialisation::getId, Function.identity()));

        return cards.stream().map(c -> {
            Institution institution = institutions.get(c.getInstitutionId());
            PressCategory category = c.getCategoryId() == null ? null
                    : categories.get(c.getCategoryId());
            Specialisation specialisation = c.getSpecialisationId() == null ? null
                    : specialisations.get(c.getSpecialisationId());

            String cannotGrant = cannotGrantReason(c);

            return new CardResponse(
                    c.getId(), c.getInstitutionId(),
                    institution == null ? "—" : institution.getNameFr(),
                    c.getFullName(), c.getIdentityNumber(),
                    c.getBirthdate(), c.getBirthplace(), c.getJobTitle(),
                    category == null ? null : category.getLabelFr(),
                    specialisation == null ? null : specialisation.getLabelFr(),
                    c.getPhotoPath() != null,
                    c.isGranted(), c.getCardNumber(),
                    c.getIssuedAt(), c.getExpiresAt(),
                    c.getStatus().name(), c.getStatus().labelFr(),
                    c.isExpired(),
                    cannotGrant == null, cannotGrant,
                    c.getFiledAt());
        }).toList();
    }

    /**
     * Why this filing cannot be granted, or null.
     *
     * ⚠️ THE PHOTOGRAPH IS NOT AMONG THE REASONS.
     *
     * A card may be granted without one — the institution attaches it
     * afterwards, and findProducible keeps the card out of the printer's
     * queue until it does. Requiring it here would make the Ministry wait on
     * the institution for something it can supply later.
     */
    private String cannotGrantReason(InstitutionalCard card) {
        if (card.isGranted()) {
            return "Cette fiche a déjà été octroyée.";
        }
        if (card.getIdentityNumber() == null || card.getIdentityNumber().isBlank()) {
            return "NNI ou passeport manquant : la carte ne peut pas être signée.";
        }
        if (repository.holderHasLiveCard(card.getInstitutionId(), card.getIdentityNumber())) {
            return "Cette personne détient déjà une carte en cours dans cette institution.";
        }
        return null;
    }

    private Long actorId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
