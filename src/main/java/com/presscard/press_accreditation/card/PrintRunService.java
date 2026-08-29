package com.presscard.press_accreditation.card;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes down what left the building.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ ONE PLACE, BECAUSE THERE ARE TWO WAYS OUT.
 *
 * A printer downloads assets; an administrator downloads a signed PDF. Both
 * are production, both belong in one history — and recorded separately they
 * would drift, leaving "how many times has this card been produced?" with two
 * answers.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class PrintRunService {

    private static final Logger log = LoggerFactory.getLogger("PRINT_RUN");

    private final PrintRunRepository runRepository;
    private final PrintRunCardRepository runCardRepository;

    public PrintRunService(PrintRunRepository runRepository,
                           PrintRunCardRepository runCardRepository) {
        this.runRepository = runRepository;
        this.runCardRepository = runCardRepository;
    }

    /**
     * Record a run.
     *
     * ⚠️ CALLED AFTER THE FILE IS BUILT, NEVER BEFORE. A run recorded for an
     * archive that failed to assemble is a line saying cards left when they
     * did not — and the count it feeds is what an administrator will one day
     * ask a contractor to explain.
     */
    @Transactional
    public PrintRun record(Long actorId, PrintRun.Kind kind,
                           Long sessionId, String layout, List<Long> cardIds) {

        PrintRun run = runRepository.save(PrintRun.builder()
                .printedBy(actorId)
                .sessionId(sessionId)
                .kind(kind)
                .layout(layout)
                .cardCount(cardIds.size())
                .build());

        runCardRepository.saveAll(cardIds.stream()
                .map(cardId -> PrintRunCard.builder()
                        .runId(run.getId())
                        .cardId(cardId)
                        .build())
                .toList());

        log.info("PRINT_RUN id={} actor={} kind={} session={} cards={}",
                run.getId(), actorId, kind, sessionId, cardIds.size());
        return run;
    }

    /**
     * How many times each of these cards has been produced.
     *
     * ⚠️ FROM THE RUNS, never from cards.print_count.
     *
     * That column counts PDF generation. The printer never generates one — it
     * takes assets — so on every card they produce it stays at zero. Reading
     * it would report each printed card as unprinted.
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> countsFor(List<Long> cardIds) {
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : runRepository.countByCardIds(cardIds)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Record an honour card run.
     *
     * ⚠️ THE SAME TABLE as ordinary production, which is the whole reason
     * print_run_cards was rebuilt with a surrogate key. Two histories would
     * give two answers to "how many times has this been produced", and the
     * printer produces both kinds in the same afternoon.
     *
     * No session: an honour card belongs to no cohort. No layout: there is no
     * PDF.
     */
    @Transactional
    public PrintRun recordHonour(Long actorId, List<Long> honourCardIds) {
        PrintRun run = runRepository.save(PrintRun.builder()
                .printedBy(actorId)
                .sessionId(null)
                .kind(PrintRun.Kind.ASSETS)
                .layout(null)
                .cardCount(honourCardIds.size())
                .build());

        runCardRepository.saveAll(honourCardIds.stream()
                .map(id -> PrintRunCard.builder()
                        .runId(run.getId())
                        .honourCardId(id)
                        .build())
                .toList());

        log.info("PRINT_RUN id={} actor={} kind=ASSETS honour cards={}",
                run.getId(), actorId, honourCardIds.size());
        return run;
    }
}
