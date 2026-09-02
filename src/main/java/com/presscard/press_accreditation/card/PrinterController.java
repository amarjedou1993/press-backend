package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.error.CardNotIssuableException;
import com.presscard.press_accreditation.honour.HonourArchiveService;
import com.presscard.press_accreditation.honour.HonourCard;
import com.presscard.press_accreditation.honour.HonourCardService;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The card producer's surface.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WHAT THIS ROLE NEVER RECEIVES: the signed card PDF.
 *
 * A printer is typically EXTERNAL to the Ministry. They get the production
 * assets — photograph, verification QR, reference preview — which is what
 * their software needs, and nothing more. The Ministry's layout and its
 * signature do not leave.
 *
 * That is not a restriction imposed on them; it is what makes an outside
 * contractor tenable. An account revoked in one click, rather than a person
 * who holds the signed document.
 *
 * ⚠️ AND NOTHING HERE GATES A REPRINT.
 *
 * A misprint is normal — a jam, a spent ribbon, forty ruined cards. A
 * permission gate on a contractor is a control that gets worked around, and a
 * bypassed rule gives the appearance of control with none of the substance.
 * So reprints are free, every one is recorded, and a card produced eleven
 * times becomes a question somebody asks.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/printer")
@PreAuthorize("hasAnyRole('PRINTER', 'SUPER_ADMIN')")
public class PrinterController {

    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    /* ── contracts ── */

    public record PrintableSession(Long sessionId, String label, long cardCount) {}

    /** A card as the producer reads it. */
    public record PrintableCard(
            Long cardId,
            String cardNumber,
            String holderFullName,
            String categoryLabelFr,
            String specialisationFr,
            String institution,
            LocalDate issuedAt,
            LocalDate expiresAt,
            Long sessionId,
            String sessionLabel,
            /**
             * How many production runs have included this card.
             *
             * ⚠️ Shown BEFORE selection, not after. A producer choosing forty
             * cards should see which ones have been out before, because that
             * is the moment the information is useful — and because a count
             * surfaced at the point of decision is a control that needs no
             * gate behind it.
             */
            long producedCount
    ) {}

    public record ArchiveRequest(
            @NotEmpty(message = "Sélectionnez au moins une carte.")
            List<Long> cardIds,
            /** Recorded on the run; null when a selection spans sessions. */
            Long sessionId
    ) {}

    public record RunSummary(
            Long id,
            OffsetDateTime printedAt,
            String actorName,
            Long sessionId,
            String sessionLabel,
            String kind,
            int cardCount
    ) {}

    /** An honour card as the producer reads it. */
    public record PrintableHonourCard(
            Long cardId,
            String cardNumber,
            String holderFullName,
            String categoryLabelFr,
            String institution,
            LocalDate issuedAt,
            LocalDate expiresAt,
            /** How many runs have included it — shown BEFORE selection. */
            long producedCount
    ) {}

    public record HonourArchiveRequest(
            @NotEmpty(message = "Sélectionnez au moins une carte.")
            List<Long> cardIds
    ) {}

    private final CardRepository cardRepository;
    private final CardArchiveService archiveService;
    private final PrintRunService printRunService;
    private final PrintRunRepository runRepository;
    private final ApplicationRepository applicationRepository;
    private final PressCategoryRepository categoryRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final HonourCardService honourCardService;
    private final HonourArchiveService honourArchiveService;

    public PrinterController(CardRepository cardRepository,
                             CardArchiveService archiveService,
                             PrintRunService printRunService,
                             PrintRunRepository runRepository,
                             ApplicationRepository applicationRepository,
                             PressCategoryRepository categoryRepository,
                             SessionRepository sessionRepository,
                             UserRepository userRepository,
                             HonourCardService honourCardService,
                             HonourArchiveService honourArchiveService) {
        this.cardRepository = cardRepository;
        this.archiveService = archiveService;
        this.printRunService = printRunService;
        this.runRepository = runRepository;
        this.applicationRepository = applicationRepository;
        this.categoryRepository = categoryRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.honourCardService = honourCardService;
        this.honourArchiveService = honourArchiveService;
    }

    /* ══ the sessions worth opening ══ */

    /**
     * Sessions that have at least one producible card.
     *
     * ⚠️ DERIVED, not listed. A session with nothing to produce is an empty
     * promise in a dropdown — the producer opens it, finds nothing, and
     * learns to distrust the list.
     *
     * ⚠️ AND COUNTED IN ONE QUERY. This previously ran
     * findProducibleBySession(...).size() per session — loading every card of
     * every session in order to count them, and discarding the rows. Fine at
     * three sessions; a full year's register at twelve.
     */
    @GetMapping("/sessions")
    @Transactional(readOnly = true)
    public List<PrintableSession> sessions() {
        List<Object[]> counts = cardRepository.countProducibleBySession(CardStatus.VALID);
        if (counts.isEmpty()) {
            return List.of();
        }

        Map<Long, Session> index = sessionIndex();

        return counts.stream()
                .map(row -> new PrintableSession(
                        (Long) row[0],
                        sessionLabel(index.get((Long) row[0])),
                        (Long) row[1]))
                .sorted(Comparator.comparing(PrintableSession::sessionId).reversed())
                .toList();
    }

    /* ══ the cards ══ */

    /**
     * ⚠️ FIVE QUERIES, WHATEVER THE SESSION'S SIZE.
     *
     * toPrintable made three lookups a row — application, holder, category —
     * so a cohort of two hundred cost six hundred sequential round trips on
     * the one screen a producer opens to do their entire job.
     */
    @GetMapping("/cards")
    @Transactional(readOnly = true)
    public List<PrintableCard> cards(@RequestParam Long sessionId) {
        List<Card> cards = cardRepository.findProducibleBySession(sessionId, CardStatus.VALID);
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

        Map<Long, User> holders = userRepository.findAllById(candidateIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Map<Long, PressCategory> categories = categoryIndex();
        Map<Long, Session> sessions = sessionIndex();

        Map<Long, Long> counts = printRunService.countsFor(
                cards.stream().map(Card::getId).toList());

        return cards.stream()
                .map(card -> toPrintable(card, applications, holders,
                        categories, sessions, counts))
                .toList();
    }

    /* ══ the archive ══ */

    /**
     * The production assets, as a ZIP — and a line in the history.
     *
     * ⚠️ THE SAME SERVICE THE MINISTRY USES. One archive format, one QR, one
     * folder convention. A second implementation "for the printer" would be
     * the same file built twice, and the two would drift.
     */
    @PostMapping("/archive")
    public ResponseEntity<byte[]> archive(@Valid @RequestBody ArchiveRequest request,
                                          Principal principal) {
        Long actorId = actorId(principal);

        // ⚠️ RE-VALIDATED SERVER-SIDE. The screen only offers producible
        // cards, but the request carries ids — and a suspended card must not
        // be produced because a page was left open across the suspension.
        List<Long> allowed = cardRepository.findAllById(request.cardIds()).stream()
                .filter(c -> c.getStatus() == CardStatus.VALID && !c.isExpired())
                .map(Card::getId)
                .toList();

        if (allowed.isEmpty()) {
            throw new CardNotIssuableException(
                    "Aucune des cartes sélectionnées n'est valable pour la production.");
        }
        CardArchiveService.ArchiveResult result = archiveService.archive(allowed);

        // Recorded AFTER the file exists: a run written for an archive that
        // failed to assemble is a line saying cards left when they did not.
        printRunService.record(actorId, PrintRun.Kind.ASSETS,
                request.sessionId(), null, allowed);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cartes-%s.zip\"".formatted(LocalDate.now()))
                .header("X-Archive-Included", String.valueOf(result.included()))
                .header("X-Archive-Skipped", String.valueOf(result.skipped()))
                .body(result.zip());
    }

    /* ══ honour cards ══ */

    /**
     * Honour cards awaiting production.
     *
     * ⚠️ NO SESSION FILTER, and there is nothing to filter by. An honour card
     * belongs to no cohort — it is granted one at a time, on its own occasion.
     * A session select here would be an empty control.
     */
    @GetMapping("/honour-cards")
    @Transactional(readOnly = true)
    public List<PrintableHonourCard> honourCards() {
        List<HonourCard> cards = honourCardService.producible();
        if (cards.isEmpty()) {
            return List.of();
        }

        Map<Long, PressCategory> categories = categoryIndex();

        // One query for the whole list, as for ordinary cards.
        Map<Long, Long> counts = runRepository
                .countByHonourCardIds(cards.stream().map(HonourCard::getId).toList())
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return cards.stream()
                .map(card -> {
                    PressCategory category = card.getCategoryId() == null ? null
                            : categories.get(card.getCategoryId());
                    return new PrintableHonourCard(
                            card.getId(),
                            card.getCardNumber(),
                            card.getFullName(),
                            category == null ? "—" : category.getLabelFr(),
                            card.getInstitution(),
                            card.getIssuedAt(),
                            card.getExpiresAt(),
                            counts.getOrDefault(card.getId(), 0L));
                })
                .toList();
    }

    /**
     * The honour cards' production assets.
     *
     * ⚠️ PHOTOGRAPH AND QR ONLY — no reference PDF, unlike an ordinary card's
     * archive. There is nothing to render: CardPdfService lays out a card from
     * a dossier, and an honour card has none.
     *
     * Which suits the requirement, and reduces what leaves: one fewer artefact
     * carrying the Ministry's layout out of the building.
     */
    @PostMapping("/honour-archive")
    public ResponseEntity<byte[]> honourArchive(
            @Valid @RequestBody HonourArchiveRequest request,
            Principal principal) {

        Long actorId = actorId(principal);

        // ⚠️ RE-VALIDATED SERVER-SIDE, as for ordinary cards: the screen only
        // offers producible ones, but the request carries ids — and a card
        // suspended since the page was opened must not be produced.
        List<Long> producible = honourCardService.producible().stream()
                .map(HonourCard::getId)
                .filter(request.cardIds()::contains)
                .toList();

        if (producible.isEmpty()) {
            throw new CardNotIssuableException(
                    "Aucune des cartes sélectionnées n'est valable pour la production.");
        }

        HonourArchiveService.ArchiveResult result = honourArchiveService.archive(producible);

        // Recorded AFTER the file exists, and into the SAME history as
        // ordinary production — one answer to "what left the building".
        printRunService.recordHonour(actorId, producible);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"cartes-honneur-%s.zip\"".formatted(LocalDate.now()))
                .header("X-Archive-Included", String.valueOf(result.included()))
                .header("X-Archive-Skipped", String.valueOf(result.skipped()))
                .body(result.zip());
    }

    /* ══ the history ══ */

    /**
     * What this producer has produced.
     *
     * ⚠️ THEIR OWN, not everyone's — even for an administrator reaching this
     * endpoint. The Ministry's view of every run lives under /api/admin,
     * where it belongs with the rest of the oversight.
     */
    @GetMapping("/history")
    @Transactional(readOnly = true)
    public List<RunSummary> history(Principal principal,
                                    @RequestParam(defaultValue = "50") int limit) {
        Long actorId = actorId(principal);

        List<PrintRun> runs = runRepository.findByPrintedByOrderByPrintedAtDesc(
                actorId, PageRequest.of(0, Math.min(limit, 200)));
        if (runs.isEmpty()) {
            return List.of();
        }

        Map<Long, Session> sessions = sessionIndex();

        /*
         * ⚠️ The actor's name looked up ONCE, not per run.
         *
         * Every run in this list has the same printedBy — it is their own
         * history — so fifty rows made fifty identical queries for the same
         * name.
         */
        String actorName = userRepository.findById(actorId)
                .map(User::getFullName).orElse("—");

        return runs.stream()
                .map(run -> toSummary(run, actorName, sessions))
                .toList();
    }

    /* ══ internals ══ */

    /**
     * ⚠️ NO REPOSITORY CALLS, AND NONE MAY BE ADDED.
     *
     * A lookup placed here is a lookup per card, and its cost will not show in
     * a code review — it will show as a production queue that takes seconds
     * to open. Anything new is batched in cards() above.
     */
    private PrintableCard toPrintable(Card card,
                                      Map<Long, Application> applications,
                                      Map<Long, User> holders,
                                      Map<Long, PressCategory> categories,
                                      Map<Long, Session> sessions,
                                      Map<Long, Long> counts) {
        Application application = card.getApplicationId() == null ? null
                : applications.get(card.getApplicationId());
        User holder = application == null ? null
                : holders.get(application.getCandidateId());
        PressCategory category = application == null ? null
                : categories.get(application.getCategoryId());
        Session session = application == null ? null
                : sessions.get(application.getSessionId());

        return new PrintableCard(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category == null ? "—" : category.getLabelFr(),
                card.getSpecialisationFr(),
                card.getInstitution(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                session == null ? null : session.getId(),
                sessionLabel(session),
                counts.getOrDefault(card.getId(), 0L));
    }

    private RunSummary toSummary(PrintRun run, String actorName,
                                 Map<Long, Session> sessions) {
        return new RunSummary(
                run.getId(),
                run.getPrintedAt(),
                actorName,
                run.getSessionId(),
                sessionLabel(run.getSessionId() == null ? null
                        : sessions.get(run.getSessionId())),
                run.getKind().name(),
                run.getCardCount());
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

    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
    }

    private Long actorId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
