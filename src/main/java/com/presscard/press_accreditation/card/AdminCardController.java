//package com.presscard.press_accreditation.card;
//
//import com.presscard.press_accreditation.application.Application;
//import com.presscard.press_accreditation.application.ApplicationRepository;
//import com.presscard.press_accreditation.application.ApplicationStatus;
//import com.presscard.press_accreditation.category.PressCategory;
//import com.presscard.press_accreditation.category.PressCategoryRepository;
//import com.presscard.press_accreditation.profile.CandidateProfile;
//import com.presscard.press_accreditation.profile.CandidateProfileRepository;
//import com.presscard.press_accreditation.user.User;
//import com.presscard.press_accreditation.user.UserRepository;
//import jakarta.validation.Valid;
//import jakarta.validation.constraints.NotEmpty;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.bind.annotation.*;
//
//import java.security.Principal;
//import java.time.LocalDate;
//import java.util.List;
//
///**
// * Card issuance, for the super admin.
// *
// * SUPER_ADMIN only, and deliberately: the commission decides who is entitled
// * to a card, but the Authority issues it. Keeping those two hands separate is
// * what makes the credential HAPA's act rather than one reviewer's.
// */
//@RestController
//@RequestMapping("/api/admin/cards")
//@PreAuthorize("hasRole('SUPER_ADMIN')")
//public class AdminCardController {
//
//    /* ── contracts ── */
//
//    /** A dossier awaiting its card. */
//    public record IssuableResponse(
//            Long applicationId,
//            String candidateFullName,
//            String categoryLabelFr,
//            String identityNumber,
//            boolean hasPhoto,
//            /** Non-null when the card cannot be issued — shown before trying. */
//            String blockerFr
//    ) {}
//
//    public record CardResponse(
//            Long cardId,
//            String cardNumber,
//            String holderFullName,
//            String categoryLabelFr,
//            LocalDate issuedAt,
//            LocalDate expiresAt,
//            String status,
//            String statusLabelFr,
//            boolean expired,
//            int printCount
//    ) {}
//
//    public record IssueRequest(
//            @NotEmpty(message = "Sélectionnez au moins une candidature.")
//            List<Long> applicationIds
//    ) {}
//
//    public record PrintRequest(
//            @NotEmpty(message = "Sélectionnez au moins une carte.")
//            List<Long> cardIds,
//            /** SEQUENTIAL for a card printer, INTERLEAVED for office duplex. */
//            CardPdfService.PageLayout layout
//    ) {}
//
//    private final CardService cardService;
//    private final CardPdfService pdfService;
//    private final CardRegistryExporter exporter;
//    private final CardRepository cardRepository;
//    private final ApplicationRepository applicationRepository;
//    private final CandidateProfileRepository profileRepository;
//    private final PressCategoryRepository categoryRepository;
//    private final UserRepository userRepository;
//
//    public AdminCardController(CardService cardService,
//                               CardPdfService pdfService,
//                               CardRegistryExporter exporter,
//                               CardRepository cardRepository,
//                               ApplicationRepository applicationRepository,
//                               CandidateProfileRepository profileRepository,
//                               PressCategoryRepository categoryRepository,
//                               UserRepository userRepository) {
//        this.cardService = cardService;
//        this.pdfService = pdfService;
//        this.exporter = exporter;
//        this.cardRepository = cardRepository;
//        this.applicationRepository = applicationRepository;
//        this.profileRepository = profileRepository;
//        this.categoryRepository = categoryRepository;
//        this.userRepository = userRepository;
//    }
//
//    /* ══ what is waiting ══ */
//
//    /**
//     * Accepted dossiers with no card yet.
//     *
//     * Each carries its BLOCKER if one exists — a missing photograph is
//     * discovered here, before a batch of two hundred, rather than in its
//     * failure list.
//     */
//    @GetMapping("/issuable")
//    @Transactional(readOnly = true)
//    public List<IssuableResponse> issuable() {
//        return applicationRepository.findByStatus(ApplicationStatus.ACCEPTED).stream()
//                .filter(a -> !cardRepository.existsByApplicationId(a.getId()))
//                .map(this::toIssuable)
//                .toList();
//    }
//
//    /* ══ issuing ══ */
//
//    @PostMapping("/issue")
//    public CardService.BatchResult issue(@Valid @RequestBody IssueRequest request,
//                                         Principal principal) {
//        return cardService.issueMany(request.applicationIds(), currentUserId(principal));
//    }
//
//    /* ══ the registry ══ */
//
//    @GetMapping
//    @Transactional(readOnly = true)
//    public List<CardResponse> registry() {
//        return cardRepository.findAllByOrderByIssuedAtDesc().stream()
//                .map(this::toCardResponse)
//                .toList();
//    }
//
//    @GetMapping("/session/{sessionId}")
//    @Transactional(readOnly = true)
//    public List<CardResponse> bySession(@PathVariable Long sessionId) {
//        return cardRepository.findBySession(sessionId).stream()
//                .map(this::toCardResponse)
//                .toList();
//    }
//
//    /* ══ printing ══ */
//
//    /** One card, two pages. */
//    @GetMapping("/{cardId}/pdf")
//    public ResponseEntity<byte[]> printOne(@PathVariable Long cardId) {
//        Card card = cardRepository.findById(cardId).orElseThrow();
//        byte[] pdf = pdfService.render(cardId);
//        recordPrint(card);
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.APPLICATION_PDF)
//                .header(HttpHeaders.CONTENT_DISPOSITION,
//                        "attachment; filename=\"carte-%s.pdf\"".formatted(card.getCardNumber()))
//                .body(pdf);
//    }
//
//    /** A batch, as one file. */
//    @PostMapping("/print")
//    public ResponseEntity<byte[]> printMany(@Valid @RequestBody PrintRequest request) {
//        CardPdfService.PageLayout layout = request.layout() == null
//                ? CardPdfService.PageLayout.INTERLEAVED
//                : request.layout();
//
//        byte[] pdf = pdfService.renderBatch(request.cardIds(), layout);
//        cardRepository.findAllById(request.cardIds()).forEach(this::recordPrint);
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.APPLICATION_PDF)
//                .header(HttpHeaders.CONTENT_DISPOSITION,
//                        "attachment; filename=\"cartes-%s-%s.pdf\""
//                                .formatted(layout.name().toLowerCase(), LocalDate.now()))
//                .body(pdf);
//    }
//
//    /* ══ the spreadsheet ══ */
//
//    @GetMapping("/export")
//    public ResponseEntity<byte[]> export() {
//        byte[] workbook = exporter.export(
//                cardRepository.findAllByOrderByIssuedAtDesc(),
//                "Registre des cartes de presse");
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.parseMediaType(
//                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
//                .header(HttpHeaders.CONTENT_DISPOSITION,
//                        "attachment; filename=\"registre-cartes-%s.xlsx\"".formatted(LocalDate.now()))
//                .body(workbook);
//    }
//
//    @GetMapping("/export/session/{sessionId}")
//    public ResponseEntity<byte[]> exportSession(@PathVariable Long sessionId) {
//        byte[] workbook = exporter.export(
//                cardRepository.findBySession(sessionId),
//                "Cartes de presse — session n° " + sessionId);
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.parseMediaType(
//                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
//                .header(HttpHeaders.CONTENT_DISPOSITION,
//                        "attachment; filename=\"cartes-session-%d.xlsx\"".formatted(sessionId))
//                .body(workbook);
//    }
//
//    /* ══ helpers ══ */
//
//    /**
//     * A reprint is the same accreditation, a new artefact — so the count and
//     * the timestamp move, and the card number never does.
//     */
//    private void recordPrint(Card card) {
//        card.setPrintCount(card.getPrintCount() + 1);
//        card.setPrintedAt(java.time.OffsetDateTime.now());
//        cardRepository.save(card);
//    }
//
//    private IssuableResponse toIssuable(Application application) {
//        User candidate = userRepository.findById(application.getCandidateId()).orElse(null);
//        CandidateProfile profile = candidate == null ? null
//                : profileRepository.findById(candidate.getId()).orElse(null);
//        String category = categoryRepository.findById(application.getCategoryId())
//                .map(PressCategory::getLabelFr).orElse("—");
//
//        boolean hasPhoto = profile != null && profile.getPhotoPath() != null;
//        String identity = profile == null ? null
//                : (profile.getNni() != null ? profile.getNni() : profile.getPassportNo());
//
//        // Surfaced BEFORE a batch runs, not discovered inside its failures.
//        String blocker = profile == null
//                ? "Profil du candidat introuvable."
//                : !hasPhoto
//                        ? "Aucune photographie : la carte ne peut pas être éditée."
//                        : null;
//
//        return new IssuableResponse(
//                application.getId(),
//                candidate == null ? "—" : candidate.getFullName(),
//                category,
//                identity == null ? "—" : identity,
//                hasPhoto,
//                blocker);
//    }
//
//    private CardResponse toCardResponse(Card card) {
//        Application application = applicationRepository
//                .findById(card.getApplicationId()).orElse(null);
//        User holder = application == null ? null
//                : userRepository.findById(application.getCandidateId()).orElse(null);
//        String category = application == null ? "—"
//                : categoryRepository.findById(application.getCategoryId())
//                        .map(PressCategory::getLabelFr).orElse("—");
//
//        boolean expired = card.isExpired();
//
//        return new CardResponse(
//                card.getId(),
//                card.getCardNumber(),
//                holder == null ? "—" : holder.getFullName(),
//                category,
//                card.getIssuedAt(),
//                card.getExpiresAt(),
//                expired && card.getStatus() == CardStatus.VALID
//                        ? "EXPIRED" : card.getStatus().name(),
//                expired && card.getStatus() == CardStatus.VALID
//                        ? "Expirée" : card.getStatus().labelFr(),
//                expired,
//                card.getPrintCount());
//    }
//
//    private Long currentUserId(Principal principal) {
//        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
//    }
//}

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            /** The session that produced this card. */
            Long sessionId,
            /** "Session du 12 mars 2026" — composed once, here. */
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

    private final CardService cardService;
    private final CardPdfService pdfService;
    private final CardRegistryExporter exporter;
    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final CandidateProfileRepository profileRepository;
    private final PressCategoryRepository categoryRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AdminCardController(CardService cardService,
                               CardPdfService pdfService,
                               CardRegistryExporter exporter,
                               CardRepository cardRepository,
                               ApplicationRepository applicationRepository,
                               CandidateProfileRepository profileRepository,
                               PressCategoryRepository categoryRepository,
                               SessionRepository sessionRepository,
                               UserRepository userRepository) {
        this.cardService = cardService;
        this.pdfService = pdfService;
        this.exporter = exporter;
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.profileRepository = profileRepository;
        this.categoryRepository = categoryRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/issuable")
    @Transactional(readOnly = true)
    public List<IssuableResponse> issuable() {
        return applicationRepository.findByStatus(ApplicationStatus.ACCEPTED).stream()
                .filter(a -> !cardRepository.existsByApplicationId(a.getId()))
                .map(this::toIssuable)
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
        // ⚠️ Sessions loaded ONCE, not per card. toCardResponse already makes
        // three lookups a row; a fourth would put the registry at four
        // queries per card, and a session catalogue is a handful of rows.
        Map<Long, Session> sessions = sessionIndex();

        return cardRepository.findAllByOrderByIssuedAtDesc().stream()
                .map(card -> toCardResponse(card, sessions))
                .toList();
    }

    @GetMapping("/session/{sessionId}")
    @Transactional(readOnly = true)
    public List<CardResponse> bySession(@PathVariable Long sessionId) {
        Map<Long, Session> sessions = sessionIndex();

        return cardRepository.findBySession(sessionId).stream()
                .map(card -> toCardResponse(card, sessions))
                .toList();
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

    /** "Session du 12 mars 2026". */
    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
    }

    private IssuableResponse toIssuable(Application application) {
        User candidate = userRepository.findById(application.getCandidateId()).orElse(null);
        CandidateProfile profile = candidate == null ? null
                : profileRepository.findById(candidate.getId()).orElse(null);
        String category = categoryRepository.findById(application.getCategoryId())
                .map(PressCategory::getLabelFr).orElse("—");

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
                category,
                identity == null ? "—" : identity,
                hasPhoto,
                blocker);
    }

    private CardResponse toCardResponse(Card card, Map<Long, Session> sessions) {
        Application application = applicationRepository
                .findById(card.getApplicationId()).orElse(null);
        User holder = application == null ? null
                : userRepository.findById(application.getCandidateId()).orElse(null);
        String category = application == null ? "—"
                : categoryRepository.findById(application.getCategoryId())
                .map(PressCategory::getLabelFr).orElse("—");

        Session session = application == null ? null
                : sessions.get(application.getSessionId());

        boolean expired = card.isExpired();

        return new CardResponse(
                card.getId(),
                card.getCardNumber(),
                holder == null ? "—" : holder.getFullName(),
                category,
                card.getIssuedAt(),
                card.getExpiresAt(),
                expired && card.getStatus() == CardStatus.VALID
                        ? "EXPIRED" : card.getStatus().name(),
                expired && card.getStatus() == CardStatus.VALID
                        ? "Expirée" : card.getStatus().labelFr(),
                expired,
                card.getPrintCount(),
                session == null ? null : session.getId(),
                sessionLabel(session));
    }

    private Long currentUserId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}

