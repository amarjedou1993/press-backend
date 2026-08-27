package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.card.Card;
import com.presscard.press_accreditation.card.CardRepository;
import com.presscard.press_accreditation.card.PrintRun;
import com.presscard.press_accreditation.card.PrintRunRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What the Ministry sees of production.
 *
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**).
 *
 * ⚠️ EVERYONE'S RUNS, unlike /api/printer/history which shows only the
 * caller's own. Oversight is the difference between the two endpoints, and
 * one path serving both would have meant a producer seeing every other
 * producer's work.
 */
@RestController
@RequestMapping("/api/admin/production")
public class AdminProductionController {

    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    /**
     * How often something has been produced more than once.
     *
     * ⚠️ NOT AN ALERT, AND NOT A BLOCK.
     *
     * A misprint is normal — a jam, a spent ribbon, a batch fed crooked. A
     * gate on an external contractor would only be worked around, and a rule
     * people bypass gives the appearance of control with none of the
     * substance.
     *
     * So reprints are free and this is what remains: a number an
     * administrator can look at, and the cards behind it. Eleven runs on one
     * card is a question worth asking. Two is a Tuesday.
     */
    public record ProductionStats(
            long runs,
            long cardsProduced,
            long reprintedCards,
            List<ReprintedCard> mostReprinted
    ) {}

    public record ReprintedCard(
            Long cardId,
            String cardNumber,
            String holderFullName,
            long runCount
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

    private final PrintRunRepository runRepository;
    private final CardRepository cardRepository;
    private final ApplicationRepository applicationRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public AdminProductionController(PrintRunRepository runRepository,
                                     CardRepository cardRepository,
                                     ApplicationRepository applicationRepository,
                                     SessionRepository sessionRepository,
                                     UserRepository userRepository) {
        this.runRepository = runRepository;
        this.cardRepository = cardRepository;
        this.applicationRepository = applicationRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    /** The figures for the dashboard. */
    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ProductionStats stats() {
        /*
         * ⚠️ A THOUSAND RUNS, IN MEMORY, TO SUM ONE COLUMN.
         *
         * Fine for a first year — dozens of runs. The remedy is
         *   SELECT COUNT(*), SUM(card_count) FROM print_runs
         * an aggregate that loads nothing.
         *
         * Worth doing past a hundred runs. Not before: this version reads at
         * a glance, and a native query is one more thing to maintain.
         */
        List<PrintRun> runs = runRepository.findAllByOrderByPrintedAtDesc(
                PageRequest.of(0, 1000));

        long cardsProduced = runs.stream().mapToLong(PrintRun::getCardCount).sum();

        // Cards that have been in two or more runs.
        List<Object[]> repeated = runRepository.cardsProducedAtLeast(2L);

        List<ReprintedCard> top = repeated.stream()
                .limit(10)
                .map(row -> {
                    Long cardId = (Long) row[0];
                    long count = (Long) row[1];
                    Card card = cardRepository.findById(cardId).orElse(null);
                    return new ReprintedCard(
                            cardId,
                            card == null ? "—" : card.getCardNumber(),
                            holderNameOf(card),
                            count);
                })
                .toList();

        return new ProductionStats(runs.size(), cardsProduced, repeated.size(), top);
    }

    /** Every run, newest first. */
    @GetMapping("/runs")
    @Transactional(readOnly = true)
    public List<RunSummary> runs(@RequestParam(defaultValue = "100") int limit) {
        Map<Long, Session> sessions = sessionRepository.findAll().stream()
                .collect(Collectors.toMap(Session::getId, Function.identity()));

        return runRepository
                .findAllByOrderByPrintedAtDesc(PageRequest.of(0, Math.min(limit, 500)))
                .stream()
                .map(run -> new RunSummary(
                        run.getId(),
                        run.getPrintedAt(),
                        userRepository.findById(run.getPrintedBy())
                                .map(User::getFullName).orElse("—"),
                        run.getSessionId(),
                        sessionLabel(run.getSessionId() == null ? null
                                : sessions.get(run.getSessionId())),
                        run.getKind().name(),
                        run.getCardCount()))
                .toList();
    }

    /* ══ internals ══ */

    /**
     * The holder's name.
     *
     * ⚠️ NOT ON THE CARD — unlike the photograph, the specialisation and the
     * outlet, which are snapshotted at issuance. It lives on the user,
     * reached through the application, so this walks card → application →
     * user rather than taking a shortcut.
     *
     * A broken chain gives a dash rather than an exception: a statistics
     * screen that fails to load because one card lost its dossier helps
     * nobody.
     */
    private String holderNameOf(Card card) {
        if (card == null) return "—";
        return applicationRepository.findById(card.getApplicationId())
                .map(Application::getCandidateId)
                .flatMap(userRepository::findById)
                .map(User::getFullName)
                .orElse("—");
    }

    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
    }
}
