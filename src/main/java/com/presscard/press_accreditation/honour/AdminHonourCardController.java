package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.CardStatus;
import com.presscard.press_accreditation.card.PrintRunRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
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

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    public record RenewBody(
            @NotNull(message = "validation.expiryMustBeFuture")
            LocalDate expiresAt,
            /**
             * ⚠️ OPTIONAL — the previous reason carries over when blank.
             *
             * A distinction renewed is usually renewed for the same reason.
             * Requiring it again would make the administrator retype a
             * sentence already on record, and invite a paraphrase that reads
             * as a different decision in the register.
             */
            String grantReason
    ) {}

    /**
     * Everything a response needs beyond the card itself, read ONCE.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ THIS EXISTS BECAUSE toResponse HAD AN N+1 HIDDEN IN IT.
     *
     * It called userRepository.findById(card.getGrantedBy()) — once per card.
     * list() of two hundred honour cards made two hundred reads of what is
     * almost always the same administrator, on the screen whose only job is
     * to show them all.
     *
     * The chain added two more lookups per card. Rather than pass six maps
     * through every call, they travel together — built once per request, and
     * no repository call may be added inside toResponse.
     * ───────────────────────────────────────────────────────────────────
     */
    private record Indexes(
            Map<Long, PressCategory> categories,
            Map<Long, Specialisation> specialisations,
            Map<Long, Long> produced,
            Map<Long, String> grantors,
            /** Every card's number, by id — to name a predecessor. */
            Map<Long, String> numbers,
            /** For each card, the number of the card that replaced it. */
            Map<Long, String> successors
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
             * ⚠️ THE CHAIN, BOTH WAYS.
             *
             * renewedFromCardNumber: this card replaces that one.
             * renewedByCardNumber:   this card has been replaced by that one.
             *
             * The second matters more on screen: a renewed card stays in the
             * list, now revoked, and "Retirée" without its successor reads as
             * a sanction on somebody the Ministry meant to honour.
             */
            String renewedFromCardNumber,
            String renewedByCardNumber,

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
            String cannotEditReasonFr,

            /**
             * Why this card cannot be renewed today, or null if it can.
             *
             * ⚠️ THE SAME PRINCIPLE AS cannotEditReasonFr: the server decides
             * and says why. A button offered and then refused is worse than
             * one that is not offered — and a copy of the rule in the UI is a
             * second rule, which will disagree the day the window changes.
             */
            String cannotRenewReasonFr
    ) {}

    private final HonourCardService service;
    private final PrintRunRepository runRepository;
    private final PressCategoryRepository categoryRepository;
    private final SpecialisationRepository specialisationRepository;
    private final UserRepository userRepository;
    private final PhotoStorageService photoStorage;


    public AdminHonourCardController(HonourCardService service,
                                     PrintRunRepository runRepository,
                                     PressCategoryRepository categoryRepository,
                                     SpecialisationRepository specialisationRepository,
                                     UserRepository userRepository,
                                     PhotoStorageService photoStorage
    ) {
        this.service = service;
        this.runRepository = runRepository;
        this.categoryRepository = categoryRepository;
        this.specialisationRepository = specialisationRepository;
        this.userRepository = userRepository;
        this.photoStorage = photoStorage;
    }

    /* ══ reads ══ */

    @GetMapping
    @Transactional(readOnly = true)
    public List<HonourCardResponse> list() {
        List<HonourCard> cards = service.all();
        // ⚠️ The list IS every card, so it serves as both what is shown and
        // the population the chain is read from.
        Indexes idx = indexes(cards, cards);
        return cards.stream().map(card -> toResponse(card, idx)).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public HonourCardResponse one(@PathVariable Long id) {
        HonourCard card = service.find(id);
        return toResponse(card, single(card));
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
                .body(toResponse(card, single(card)));
    }

    @PutMapping("/{id}")
    public HonourCardResponse update(@PathVariable Long id,
                                     @Valid @RequestBody GrantBody body) {
        HonourCard card = service.update(id, toRequest(body));
        return toResponse(card, single(card));
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
        return toResponse(card, single(card));
    }

    /**
     * The holder's photograph.
     *
     * ⚠️ THROUGH THE SERVER, never as a bare <img src>. The file lives outside
     * the web root and the request carries a token — the administration space
     * has no anonymous reads, and a photograph is the most identifying thing
     * in this record.
     *
     * ⚠️ AND NO-STORE. Personal data on an authenticated endpoint must not sit
     * in a proxy cache, nor in a shared machine's disk cache after the
     * administrator logs out.
     */
    @GetMapping("/{id}/photo")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> photo(@PathVariable Long id) throws IOException {
        HonourCard card = service.find(id);
        if (card.getPhotoPath() == null) {
            return ResponseEntity.notFound().build();
        }

        Path path = photoStorage.resolve(card.getPhotoPath());
        if (!Files.exists(path)) {
            // The row says it has a photograph and the disk disagrees — a
            // 404 rather than a 500: the screen shows the placeholder, and
            // the administrator can re-upload.
            return ResponseEntity.notFound().build();
        }

        String contentType = Files.probeContentType(path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        contentType != null ? contentType : "image/jpeg"))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new UrlResource(path.toUri()));
    }
    /* ══ renewal ══ */

    /**
     * Renew a card: a new B number, the holder's details carried over, the
     * predecessor retired.
     *
     * ⚠️ AVAILABLE ON A PRODUCED CARD, unlike an edit — and that is the point.
     * The card being renewed is the one in somebody's pocket.
     */
    @PostMapping("/{id}/renew")
    public ResponseEntity<HonourCardResponse> renew(@PathVariable Long id,
                                                    @Valid @RequestBody RenewBody body,
                                                    Principal principal) {
        HonourCard card = service.renew(id, body.expiresAt(),
                body.grantReason(), actorId(principal));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(card, single(card)));
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
        return toResponse(card, single(card));
    }

    /* ══ internals ══ */

    private HonourCardService.GrantRequest toRequest(GrantBody body) {
        return new HonourCardService.GrantRequest(
                body.fullName(), body.identityNumber(), body.birthdate(),
                body.birthplace(), body.categoryId(), body.specialisationId(),
                body.institution(), body.expiresAt(), body.grantReason());
    }

    /**
     * ⚠️ NO REPOSITORY CALLS, AND NONE MAY BE ADDED.
     *
     * Everything this needs arrives in `idx`, built once per request. A
     * lookup placed here is a lookup per card — the grantor's name was one,
     * and it cost two hundred queries on a list of two hundred.
     */
    private HonourCardResponse toResponse(HonourCard card, Indexes idx) {
        // EXPIRED is derived, as everywhere else — a lapsed card must never
        // read "valide" because a stored flag was not updated.
        boolean expired = card.isExpired();
        boolean lapsed = expired && card.getStatus() == CardStatus.VALID;

        boolean produced = idx.produced().getOrDefault(card.getId(), 0L) > 0;
        String successor = idx.successors().get(card.getId());

        /*
         * ───────────────────────────────────────────────────────────────
         * ⚠️ TWO WAYS A CARD CLOSES, ONE FIELD THAT SAYS SO.
         *
         * Production was the first: the plastic exists, and editing the row
         * would make the signature verify a name the card does not show.
         *
         * Renewal is the second, and it was missed. A card renewed before it
         * ever reached the printer has produced = false — so the pencil
         * stayed enabled on a card whose successor already carries the
         * holder's details. Two rows would then disagree about one person.
         *
         * The screens disable on cannotEditReasonFr, never on `produced`, so
         * both closures lock the card and each says which one it is.
         * ───────────────────────────────────────────────────────────────
         */
        String cannotEdit = produced
                ? "Cette carte a déjà été produite : ses informations ne "
                  + "peuvent plus être modifiées. Pour corriger une erreur, "
                  + "retirez-la et accordez-en une nouvelle."
                : successor != null
                  ? "Cette carte a été renouvelée et remplacée par la carte n° "
                    + successor + " : ses informations ne peuvent plus être modifiées."
                  : null;

        /*
         * ⚠️ `successor != null` IS the "already renewed" answer, and it costs
         * no query: the map was built from the list in memory.
         */
        String cannotRenew = HonourCardService.cannotRenewReasonFr(card, successor != null);

        PressCategory category = card.getCategoryId() == null ? null
                : idx.categories().get(card.getCategoryId());
        Specialisation specialisation = card.getSpecialisationId() == null ? null
                : idx.specialisations().get(card.getSpecialisationId());

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
                idx.grantors().getOrDefault(card.getGrantedBy(), "—"),
                card.getGrantReason(),
                card.getRenewedFromCardId() == null ? null
                        : idx.numbers().get(card.getRenewedFromCardId()),
                successor,
                // ⚠️ `produced` keeps its own meaning — it answers "did this
                // leave the building", which the printer's screens ask too.
                // Whether the card may be edited is cannotEditReasonFr.
                produced,
                cannotEdit,
                cannotRenew);
    }

    /**
     * The indexes for a set of cards.
     *
     * @param shown the cards being returned — produced counts and grantors are
     *              read for these only
     * @param all   the population the chain is read from. A card's successor
     *              is whichever card points at it, and that card may not be
     *              in `shown`.
     */
    private Indexes indexes(List<HonourCard> shown, List<HonourCard> all) {
        List<Long> grantorIds = shown.stream()
                .map(HonourCard::getGrantedBy)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, String> grantors = grantorIds.isEmpty() ? Map.of()
                : userRepository.findAllById(grantorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getFullName));

        Map<Long, String> numbers = all.stream()
                .collect(Collectors.toMap(HonourCard::getId, HonourCard::getCardNumber));

        Map<Long, String> successors = all.stream()
                .filter(c -> c.getRenewedFromCardId() != null)
                .collect(Collectors.toMap(
                        HonourCard::getRenewedFromCardId,
                        HonourCard::getCardNumber,
                        // A unique index forbids two successors; if one ever
                        // appears, show the first rather than throwing.
                        (a, b) -> a));

        return new Indexes(
                categoryIndex(),
                specialisationIndex(),
                producedIndex(shown.stream().map(HonourCard::getId).toList()),
                grantors,
                numbers,
                successors);
    }

    /**
     * ⚠️ THE CHAIN NEEDS EVERY CARD, even for one.
     *
     * A single card's successor is whichever card points at it — and nothing
     * on the card itself says which. service.all() is a few hundred rows, one
     * query, and it is what list() reads anyway: consistency is worth more
     * than the saving.
     */
    private Indexes single(HonourCard card) {
        return indexes(List.of(card), service.all());
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