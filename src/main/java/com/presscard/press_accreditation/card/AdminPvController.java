package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.error.CardNotIssuableException;
import com.presscard.press_accreditation.honour.HonourCard;
import com.presscard.press_accreditation.honour.HonourCardRepository;
import com.presscard.press_accreditation.institutional.InstitutionalCard;
import com.presscard.press_accreditation.institutional.InstitutionalCardRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Procès-verbaux — the signed record of who was accredited.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ REGENERABLE, AND NOTHING IS TRACKED.
 *
 * A PV states what a period contained. Anyone holding one can run the same
 * query over the same range and get the same names — which is what makes it
 * evidence rather than a printout.
 *
 * The alternative was marking cards as "covered by a PV" and generating
 * whatever remained. It fails the first time somebody spots an error: the
 * second attempt comes back empty, because the cards are already marked, and
 * the repair is a database edit.
 *
 * ⚠️ SO THE ADMINISTRATOR CHOOSES THE RANGE, and the range is printed in the
 * document. Nothing to remember, nothing to reset, nothing to repair.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/admin/pv")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPvController {

    private static final Logger log = LoggerFactory.getLogger("PV_AUDIT");

    private static final DateTimeFormatter LONG_FR =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter FILE_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ProcesVerbalService pvService;
    private final CardRegistryAssembler assembler;
    private final CardRepository cardRepository;
    private final HonourCardRepository honourCardRepository;
    private final InstitutionalCardRepository institutionalCardRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AdminPvController(ProcesVerbalService pvService,
                             CardRegistryAssembler assembler,
                             CardRepository cardRepository,
                             HonourCardRepository honourCardRepository,
                             InstitutionalCardRepository institutionalCardRepository,
                             SessionRepository sessionRepository,
                             UserRepository userRepository) {
        this.pvService = pvService;
        this.assembler = assembler;
        this.cardRepository = cardRepository;
        this.honourCardRepository = honourCardRepository;
        this.institutionalCardRepository = institutionalCardRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /* ══ contracts ══ */

    /**
     * Who sat, as the administrator confirms it.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ SUPPLIED, NOT DEDUCED — AND THAT IS A DELIBERATE REFUSAL.
     *
     * The obvious move was to read the reviewers who decided dossiers in this
     * session. It would be wrong: THE SYSTEM RECORDS DECISIONS, NOT SITTINGS.
     *
     * A member who ruled on three files from home did not sit. A member
     * present all day who signed nothing would appear nowhere. Deriving one
     * from the other invents a fact the system never observed — on a document
     * that serves as proof.
     *
     * So the screen offers the active reviewers, ticked, and the
     * administrator confirms or corrects. They know who was in the room.
     * ───────────────────────────────────────────────────────────────────
     */
    public record SessionPvRequest(List<String> commissioners) {}

    public record RangePvRequest(
            @NotNull(message = "Indiquez la date de début.") LocalDate from,
            @NotNull(message = "Indiquez la date de fin.") LocalDate to
    ) {}

    /** The names a screen offers as a starting point. */
    public record CommissionerOption(Long id, String fullName) {}

    /* ══ who to offer ══ */

    /**
     * ⚠️ ENABLED REVIEWERS, as a SUGGESTION.
     *
     * The screen ticks them; the administrator unticks whoever was absent and
     * adds anyone the system does not know — a president of the sitting, a
     * rapporteur. The list is a convenience, never the answer.
     */
    @GetMapping("/commissioners")
    @Transactional(readOnly = true)
    public List<CommissionerOption> commissioners() {
        return userRepository.findByRoleAndEnabledTrue(UserRole.REVIEWER).stream()
                .map(u -> new CommissionerOption(u.getId(), u.getFullName()))
                .toList();
    }

    /* ══ series A — a session ══ */

    /**
     * The PV of a session's cards.
     *
     * ⚠️ THE SESSION IS THE RANGE, and not two dates.
     *
     * A candidature belongs to a cohort with a beginning and an end, and the
     * commission's act is bounded by it. Asking for dates here would let an
     * administrator produce a PV spanning two sessions — a document no
     * commission could sign, because no single commission decided it.
     */
    @PostMapping("/session/{sessionId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> sessionPv(@PathVariable Long sessionId,
                                            @RequestBody SessionPvRequest request,
                                            Principal principal) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CardNotIssuableException("Session introuvable."));

        List<Card> cards = cardRepository.findBySessionIdOrderByCardNumberAsc(sessionId);

        byte[] file = pvService.build(
                ProcesVerbalService.Kind.CANDIDACY,
                new ProcesVerbalService.Context(
                        "Session du " + session.getStartDate().format(LONG_FR),
                        request.commissioners()),
                assembler.fromCards(cards));

        log.info("PV_SESSION session={} cards={} by={}",
                sessionId, cards.size(), principal.getName());

        return download(file, "pv-session-%s".formatted(
                session.getStartDate().format(FILE_DATE)));
    }

    /* ══ series B — honour cards over a period ══ */

    @PostMapping("/honour")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> honourPv(@Valid @RequestBody RangePvRequest request,
                                           Principal principal) {
        requireOrderedRange(request);

        List<HonourCard> cards = honourCardRepository
                .findByIssuedAtBetweenOrderByCardNumberAsc(request.from(), request.to());

        byte[] file = pvService.build(
                ProcesVerbalService.Kind.HONOUR,
                /*
                 * ⚠️ NO COMMISSIONERS, and the generator would ignore them
                 * anyway: Kind.HONOUR carries commissionSigns = false. An
                 * honour card is granted by the Ministry without examination,
                 * and a commission signature block on this document would be
                 * a falsehood.
                 */
                new ProcesVerbalService.Context(rangeLabel(request), List.of()),
                assembler.fromHonourCards(cards));

        log.info("PV_HONOUR from={} to={} cards={} by={}",
                request.from(), request.to(), cards.size(), principal.getName());

        return download(file, "pv-cartes-honneur-%s_%s".formatted(
                request.from().format(FILE_DATE), request.to().format(FILE_DATE)));
    }

    /* ══ series C — institutional cards over a period ══ */

    @PostMapping("/institutional")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> institutionalPv(@Valid @RequestBody RangePvRequest request,
                                                  Principal principal) {
        requireOrderedRange(request);

        List<InstitutionalCard> cards = institutionalCardRepository
                .findByIssuedAtBetweenOrderByCardNumberAsc(request.from(), request.to());

        byte[] file = pvService.build(
                ProcesVerbalService.Kind.INSTITUTIONAL,
                new ProcesVerbalService.Context(rangeLabel(request), List.of()),
                assembler.fromInstitutionalCards(cards));

        log.info("PV_INSTITUTIONAL from={} to={} cards={} by={}",
                request.from(), request.to(), cards.size(), principal.getName());

        return download(file, "pv-cartes-institutionnelles-%s_%s".formatted(
                request.from().format(FILE_DATE), request.to().format(FILE_DATE)));
    }

    /* ══ internals ══ */

    /**
     * ⚠️ A REVERSED RANGE IS REFUSED, NOT SILENTLY SWAPPED.
     *
     * Swapping them would produce a document covering a period the
     * administrator did not ask for — and the heading would say so while they
     * believed otherwise. The message names the problem instead.
     */
    private static void requireOrderedRange(RangePvRequest request) {
        if (request.to().isBefore(request.from())) {
            throw new CardNotIssuableException(
                    "La date de fin précède la date de début.");
        }
    }

    private static String rangeLabel(RangePvRequest request) {
        return "du %s au %s".formatted(
                request.from().format(LONG_FR), request.to().format(LONG_FR));
    }

    /**
     * ⚠️ Content-Disposition carries the range in the file name, as the
     * document carries it in its heading. Two PVs of the same series sitting
     * in one folder must be distinguishable without opening them.
     */
    private static ResponseEntity<byte[]> download(byte[] file, String baseName) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"%s.docx\"".formatted(baseName))
                .body(file);
    }
}
