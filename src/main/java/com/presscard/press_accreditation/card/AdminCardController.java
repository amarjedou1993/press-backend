package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.application.ApplicationStatus;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/cards")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminCardController {

    /**
     * ⚠️ CARDS ARE ISSUED AND RENEWED IN COHORTS.
     *
     * Everyone accredited in one session shares an expiry date — it comes
     * from sessions.card_expiry_date, not from each card. So "print this
     * session" is the Authority's actual unit of work, and the registry has
     * to be able to say which session a card belongs to.
     */
    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    /* ── contracts ── */

    /** A dossier awaiting its card. */
    public record IssuableResponse(
            Long applicationId,
            String candidateFullName,
            String categoryLabelFr,
            String identityNumber,
            boolean hasPhoto,
            /** Non-null when the card cannot be issued — shown before trying. */
            String blockerFr
    ) {}

    public record CardResponse(
            Long cardId,
            String cardNumber,
            String holderFullName,
            String categoryLabelFr,
            LocalDate issuedAt,
            LocalDate expiresAt,
            String status,
            String statusLabelFr,
            boolean expired,
            int printCount,
            /**
             * ⚠️ Distinct from printCount. One counts cards that went to the
             * printer; this counts material a designer collected. Merged,
             * neither number answers its own question.
             */
            int archiveCount,
            Long sessionId,
            String sessionLabel
    ) {}

    public record IssueRequest(
            @NotEmpty(message = "Sélectionnez au moins une candidature.")
            List<Long> applicationIds
    ) {}

    public record PrintRequest(
            @NotEmpty(message = "Sélectionnez au moins une carte.")
            List<Long> cardIds,
            /** SEQUENTIAL for a card printer, INTERLEAVED for office duplex. */
            CardPdfService.PageLayout layout
    ) {}

    public record ArchiveRequest(
            @NotEmpty(message = "Sélectionnez au moins une carte.")
            List<Long> cardIds
    ) {}

    private final CardService cardService;
    private final CardPdfService pdfService;
    private final CardRegistryExporter exporter;
    private final CardRepository cardRepository;
    private final CardArchiveService archiveService;
    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PressCategoryRepository categoryRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AdminCardController(CardService cardService,
                               CardPdfService pdfService,
                               CardRegistryExporter exporter,
                               CardRepository cardRepository,
                               CardArchiveService archiveService,
                               ApplicationRepository applicationRepository,
                               CandidateProfileRepository profileRepository,
                               PressCategoryRepository categoryRepository,
                               SessionRepository sessionRepository,
                               UserRepository userRepository) {
        this.cardService = cardService;
        this.pdfService = pdfService;
        this.exporter = exporter;
        this.cardRepository = cardRepository;
        this.archiveService = archiveService;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.categoryRepository = categoryRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /* ══ awaiting a card ══ */

    /**
     * Dossiers accepted but not yet carded.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ THIS WAS THE MOST EXPENSIVE ENDPOINT IN THE FILE.
     *
     * It ran one existsByApplicationId per ACCEPTED dossier, then three more
     * lookups inside the mapping — so a cohort of two hundred cost roughly
     * eight hundred sequential round trips on one pooled connection, on the
     * screen an administrator opens to issue a whole session's cards.
     *
     * Now: four queries, whatever the cohort's size.
     * ───────────────────────────────────────────────────────────────────
     */
    @GetMapping("/issuable")
    @Transactional(readOnly = true)
    public List<IssuableResponse> issuable() {
        List<Application> accepted = applicationRepository
                .findByStatus(ApplicationStatus.ACCEPTED);
        if (accepted.isEmpty()) {
            return List.of();
        }

        List<Long> applicationIds = accepted.stream().map(Application::getId).toList();

        // One query, not one existence check per dossier.
        Set<Long> alreadyIssued = new HashSet<>(
                cardRepository.findApplicationIdsWithCards(applicationIds));

        List<Application> pending = accepted.stream()
                .filter(a -> !alreadyIssued.contains(a.getId()))
                .toList();
        if (pending.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = pending.stream()
                .map(Application::getCandidateId).distinct().toList();

        Map<Long, User> users = userRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, CandidateProfile> profiles = profileRepository
                .findByUserIdIn(candidateIds).stream()
                .collect(Collectors.toMap(CandidateProfile::getUserId, Function.identity()));

        Map<Long, PressCategory> categories = categoryIndex();

        return pending.stream()
                .map(a -> toIssuable(a, users, profiles, categories))
                .toList();
    }

    /* ══ issuing ══ */

    @PostMapping("/issue")
    public CardService.BatchResult issue(@Valid @RequestBody IssueRequest request,
                                         Principal principal) {
        return cardService.issueMany(request.applicationIds(), currentUserId(principal));
    }

    /* ══ the registry ══ */

    @GetMapping
    @Transactional(readOnly = true)
    public List<CardResponse> registry() {
        return toCardResponses(cardRepository.findAllByOrderByIssuedAtDesc());
    }

    @GetMapping("/session/{sessionId}")
    @Transactional(readOnly = true)
    public List<CardResponse> bySession(@PathVariable Long sessionId) {
        return toCardResponses(cardRepository.findBySession(sessionId));
    }

    /* ══ printing ══ */

    /** One card, two pages. */
    @GetMapping("/{cardId}/pdf")
    public ResponseEntity<byte[]> printOne(@PathVariable Long cardId) {
        Card card = cardRepository.findById(cardId).orElseThrow();
        byte[] pdf = pdfService.render(cardId);
        recordPrint(card);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"carte-%s.pdf\"".formatted(card.getCardNumber()))
                .body(pdf);
    }

    /** A batch, as one file. */
    @PostMapping("/print")
    public ResponseEntity<byte[]> printMany(@Valid @RequestBody PrintRequest request) {
        CardPdfService.PageLayout layout = request.layout() == null
                ? CardPdfService.PageLayout.INTERLEAVED
                : request.layout();

        byte[] pdf = pdfService.renderBatch(request.cardIds(), layout);
        cardRepository.findAllById(request.cardIds()).forEach(this::recordPrint);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cartes-%s-%s.pdf\""
                                .formatted(layout.name().toLowerCase(), LocalDate.now()))
                .body(pdf);
    }

    /* ══ export ══ */

    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        byte[] workbook = exporter.export(
                cardRepository.findAllByOrderByIssuedAtDesc(),
                "Registre des cartes de presse");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"registre-cartes-%s.xlsx\"".formatted(LocalDate.now()))
                .body(workbook);
    }

    @GetMapping("/export/session/{sessionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportSession(@PathVariable Long sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        String label = sessionLabel(session);

        byte[] workbook = exporter.export(
                cardRepository.findBySession(sessionId),
                // ⚠️ The DATE, not the id. This title is the first line of a
                // workbook that will be printed, filed and forwarded; "session
                // n° 12" means nothing to whoever opens it a year later.
                "Cartes de presse — " + label);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        // The filename carries the date too, for the same
                        // reason: three exports in a Downloads folder must be
                        // tellable apart without opening them.
                        "attachment; filename=\"cartes-session-%s.xlsx\""
                                .formatted(session == null
                                        ? sessionId.toString()
                                        : session.getStartDate().toString()))
                .body(workbook);
    }

    /**
     * The production archive: photo, QR and reference PDF, per card.
     *
     * Not a print run — see archive_count. This is a designer collecting
     * material, possibly several times while a layout settles.
     */
    @PostMapping("/archive")
    public ResponseEntity<byte[]> archive(@Valid @RequestBody ArchiveRequest request) {
        CardArchiveService.ArchiveResult result = archiveService.archive(request.cardIds());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cartes-%s.zip\"".formatted(LocalDate.now()))
                // ⚠️ The counts travel in HEADERS. The body is a binary file
                // the browser saves directly, so there is nowhere else to say
                // "3 of 40 had no photograph".
                .header("X-Archive-Included", String.valueOf(result.included()))
                .header("X-Archive-Skipped", String.valueOf(result.skipped()))
                .body(result.zip());
    }

    /* ══ mapping ══ */

    /**
     * Map a list of cards with every lookup batched.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ FOUR QUERIES, WHATEVER THE LIST'S LENGTH.
     *
     * It was three PER CARD — application, holder, category — and the
     * registry loads every card ever issued. Two hundred cards therefore
     * meant six hundred sequential round trips, holding one connection from a
     * pool of ten for the duration.
     * ───────────────────────────────────────────────────────────────────
     */
    private List<CardResponse> toCardResponses(List<Card> cards) {
        if (cards.isEmpty()) {
            return List.of();
        }

        List<Long> applicationIds = cards.stream()
                .map(Card::getApplicationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Application> applications = applicationRepository
                .findAllById(applicationIds).stream()
                .collect(Collectors.toMap(Application::getId, Function.identity()));

        List<Long> candidateIds = applications.values().stream()
                .map(Application::getCandidateId).distinct().toList();

        Map<Long, User> users = userRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, PressCategory> categories = categoryIndex();
        Map<Long, Session> sessions = sessionIndex();

        return cards.stream()
                .map(card -> toCardResponse(card, applications, users, categories, sessions))
                .toList();
    }

    /**
     * ⚠️ NO REPOSITORY CALLS, AND NONE MAY BE ADDED.
     *
     * A lookup placed here is a lookup per card, and its cost will not show in
     * a code review — it will show as a registry that takes four seconds to
     * open, for reasons nobody can point at. Anything new is batched in
     * toCardResponses above.
     */
    private CardResponse toCardResponse(Card card,
                                        Map<Long, Application> applications,
                                        Map<Long, User> users,
                                        Map<Long, PressCategory> categories,
                                        Map<Long, Session> sessions) {
        Application application = card.getApplicationId() == null ? null
                : applications.get(card.getApplicationId());
        User holder = application == null ? null
                : users.get(application.getCandidateId());
        PressCategory category = application == null ? null
                : categories.get(application.getCategoryId());
        Session session = application == null ? null
                : sessions.get(application.getSessionId());

        boolean expired = card.isExpired();

        return new CardResponse(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category == null ? "—" : category.getLabelFr(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                expired && card.getStatus() == CardStatus.VALID
                        ? "EXPIRED" : card.getStatus().name(),
                expired && card.getStatus() == CardStatus.VALID
                        ? "Expirée" : card.getStatus().labelFr(),
                expired,
                card.getPrintCount(),
                card.getArchiveCount(),
                session == null ? null : session.getId(),
                sessionLabel(session));
    }

    /** ⚠️ NO REPOSITORY CALLS. See toCardResponse. */
    private IssuableResponse toIssuable(Application application,
                                        Map<Long, User> users,
                                        Map<Long, CandidateProfile> profiles,
                                        Map<Long, PressCategory> categories) {
        User candidate = users.get(application.getCandidateId());
        CandidateProfile profile = candidate == null ? null
                : profiles.get(candidate.getId());
        PressCategory category = categories.get(application.getCategoryId());

        boolean hasPhoto = profile != null && profile.getPhotoPath() != null;
        String identity = profile == null ? null
                : (profile.getNni() != null ? profile.getNni() : profile.getPassportNo());

        // Surfaced BEFORE a batch runs, not discovered inside its failures.
        String blocker = profile == null
                ? "Profil du candidat introuvable."
                : !hasPhoto
                  ? "Aucune photographie : la carte ne peut pas être éditée."
                  : null;

        return new IssuableResponse(
                application.getId(),
                candidate == null ? "—" : candidate.getFullName(),
                category == null ? "—" : category.getLabelFr(),
                identity == null ? "—" : identity,
                hasPhoto,
                blocker);
    }

    /* ══ helpers ══ */

    /**
     * A reprint is the same accreditation, a new artefact — so the count and
     * the timestamp move, and the card number never does.
     */
    private void recordPrint(Card card) {
        card.setPrintCount(card.getPrintCount() + 1);
        card.setPrintedAt(java.time.OffsetDateTime.now());
        cardRepository.save(card);
    }

    /** Every session, by id — a handful of rows, read once per request. */
    private Map<Long, Session> sessionIndex() {
        return sessionRepository.findAll().stream()
                .collect(Collectors.toMap(Session::getId, Function.identity()));
    }

    /** Every category, by id — seeded reference data, a dozen rows at most. */
    private Map<Long, PressCategory> categoryIndex() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));
    }

    /** "Session du 12 mars 2026". */
    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
    }

    private Long currentUserId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
