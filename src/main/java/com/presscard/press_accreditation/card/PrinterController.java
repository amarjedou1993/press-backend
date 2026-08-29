package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.error.CardNotIssuableException;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.honour.HonourArchiveService;
import com.presscard.press_accreditation.honour.HonourCard;
import com.presscard.press_accreditation.honour.HonourCardService;
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
    private final PressCategoryRepository pressCategoryRepository;


    public PrinterController(CardRepository cardRepository,
                             CardArchiveService archiveService,
                             PrintRunService printRunService,
                             PrintRunRepository runRepository,
                             ApplicationRepository applicationRepository,
                             PressCategoryRepository categoryRepository,
                             SessionRepository sessionRepository,
                             UserRepository userRepository,
                             HonourCardService honourCardService,
                             HonourArchiveService honourArchiveService,
                             PressCategoryRepository pressCategoryRepository
    ) {
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
        this.pressCategoryRepository = pressCategoryRepository;
    }

    /* ══ the sessions worth opening ══ */

    /**
     * Sessions that have at least one producible card.
     *
     * ⚠️ DERIVED, not listed. A session with nothing to produce is an empty
     * promise in a dropdown — the producer opens it, finds nothing, and
     * learns to distrust the list.
     */
    @GetMapping("/sessions")
    @Transactional(readOnly = true)
    public List<PrintableSession> sessions() {
        Map<Long, Session> index = sessionIndex();

        return cardRepository.sessionIdsWithProducibleCards(CardStatus.VALID).stream()
                .map(sessionId -> {
                    long count = cardRepository
                            .findProducibleBySession(sessionId, CardStatus.VALID).size();
                    return new PrintableSession(
                            sessionId, sessionLabel(index.get(sessionId)), count);
                })
                .sorted(Comparator.comparing(PrintableSession::sessionId).reversed())
                .toList();
    }

    /* ══ the cards ══ */

    @GetMapping("/cards")
    @Transactional(readOnly = true)
    public List<PrintableCard> cards(@RequestParam Long sessionId) {
        List<Card> cards = cardRepository.findProducibleBySession(sessionId, CardStatus.VALID);
        if (cards.isEmpty()) {
            return List.of();
        }

        Map<Long, Session> sessions = sessionIndex();
        Map<Long, Long> counts = printRunService.countsFor(
                cards.stream().map(Card::getId).toList());

        return cards.stream()
                .map(card -> toPrintable(card, sessions, counts))
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

        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        // One query for the whole list, as for ordinary cards.
        Map<Long, Long> counts = runRepository
                .countByHonourCardIds(cards.stream().map(HonourCard::getId).toList())
                .stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        return cards.stream()
                .map(card -> new PrintableHonourCard(
                        card.getId(),
                        card.getCardNumber(),
                        card.getFullName(),
                        card.getCategoryId() == null ? "—"
                                : categories.containsKey(card.getCategoryId())
                                  ? categories.get(card.getCategoryId()).getLabelFr()
                                  : "—",
                        card.getInstitution(),
                        card.getIssuedAt(),
                        card.getExpiresAt(),
                        counts.getOrDefault(card.getId(), 0L)))
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
        Map<Long, Session> sessions = sessionIndex();

        return runRepository
                .findByPrintedByOrderByPrintedAtDesc(actorId, PageRequest.of(0, Math.min(limit, 200)))
                .stream()
                .map(run -> toSummary(run, sessions))
                .toList();
    }

    /* ══ internals ══ */

    private PrintableCard toPrintable(Card card,
                                      Map<Long, Session> sessions,
                                      Map<Long, Long> counts) {
        Application application = applicationRepository
                .findById(card.getApplicationId()).orElse(null);
        User holder = application == null ? null
                : userRepository.findById(application.getCandidateId()).orElse(null);
        String category = application == null ? "—"
                : categoryRepository.findById(application.getCategoryId())
                        .map(PressCategory::getLabelFr).orElse("—");
        Session session = application == null ? null
                : sessions.get(application.getSessionId());

        return new PrintableCard(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category,
                card.getSpecialisationFr(),
                card.getInstitution(),
                card.getIssuedAt(),
                card.getExpiresAt(),
                session == null ? null : session.getId(),
                sessionLabel(session),
                counts.getOrDefault(card.getId(), 0L));
    }

    private RunSummary toSummary(PrintRun run, Map<Long, Session> sessions) {
        return new RunSummary(
                run.getId(),
                run.getPrintedAt(),
                userRepository.findById(run.getPrintedBy())
                        .map(User::getFullName).orElse("—"),
                run.getSessionId(),
                sessionLabel(run.getSessionId() == null ? null
                        : sessions.get(run.getSessionId())),
                run.getKind().name(),
                run.getCardCount());
    }

    /** Every session, by id — a handful of rows, read once per request. */
    private Map<Long, Session> sessionIndex() {
        return sessionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Session::getId, java.util.function.Function.identity()));
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
