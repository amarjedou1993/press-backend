package com.presscard.press_accreditation.document;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Decides whether an application's evidence satisfies its category's rules.
 *
 * The rules are DATA, not code: they live in document_requirements, seeded in
 * V2 and editable by HAPA without a deployment. This service interprets them.
 *
 * The interpretation has exactly two moves:
 *   · MANDATORY requirements (alternative_group IS NULL) must ALL be met.
 *   · Each ALTERNATIVE GROUP (rows sharing a group number) needs only ONE of
 *     its options met.
 *
 * Concretely, from the seed:
 *   International media → CONTRACT(1) AND WORK_LINK(3)          [two mandatory]
 *   Public employee     → WORK_CERTIFICATE(1)                   [one mandatory]
 *   Freelancer          → WORK_CERTIFICATE(1) | WEBSITE(1)
 *                         | WORK_LINK(3)                        [group 1]
 *
 * The result is deliberately RICH rather than a boolean: the submission
 * wizard renders it directly as a checklist, so the candidate always sees
 * exactly what is missing and why — the same object that decides also explains.
 */
@Service
public class CompletenessService {

    /* ── result shapes ───────────────────────────────────────── */

    /** One requirement and how the candidate currently stands against it. */
    public record RequirementStatus(
            DocumentType docType,
            String labelFr,
            String labelAr,
            boolean isFile,
            int required,
            int provided,
            boolean satisfied
    ) {
        static RequirementStatus of(DocumentRequirement r, int provided) {
            return new RequirementStatus(
                    r.getDocType(), r.getDocType().labelFr(), r.getDocType().labelAr(),
                    r.getDocType().isFile(), r.getMinCount(), provided,
                    provided >= r.getMinCount());
        }
    }

    /** A set of interchangeable options; satisfying any ONE satisfies it. */
    public record AlternativeGroup(
            int groupNumber,
            boolean satisfied,
            List<RequirementStatus> options
    ) {}

    /** The whole picture: the verdict plus everything needed to explain it. */
    public record CompletenessResult(
            boolean complete,
            List<RequirementStatus> mandatory,
            List<AlternativeGroup> alternativeGroups,
            List<String> missingFr
    ) {
        public static CompletenessResult empty() {
            return new CompletenessResult(false, List.of(), List.of(),
                    List.of("Aucune exigence documentaire n'est définie pour cette catégorie."));
        }
    }

    private final DocumentRequirementRepository requirementRepository;
    private final ApplicationDocumentRepository documentRepository;

    public CompletenessService(DocumentRequirementRepository requirementRepository,
                               ApplicationDocumentRepository documentRepository) {
        this.requirementRepository = requirementRepository;
        this.documentRepository = documentRepository;
    }

    /** Evaluate an application against its category's rules. */
    @Transactional(readOnly = true)
    public CompletenessResult evaluate(Long applicationId, Long categoryId) {
        List<DocumentRequirement> requirements =
                requirementRepository.findByCategoryId(categoryId);

//        if (requirements.isEmpty()) {
//            return CompletenessResult.empty();
//        }

        // How many valid documents of each type the candidate has provided.
        // A document flagged needs_correction does NOT count: it is precisely
        // the piece the commission asked to be replaced.
        Map<DocumentType, Long> provided = documentRepository
//                .findByApplicationIdAndNeedsCorrectionFalse(applicationId).stream()
                .findCurrentByApplicationId(applicationId).stream()
                .collect(Collectors.groupingBy(ApplicationDocument::getDocType,
                        Collectors.counting()));

        return evaluateAgainst(requirements, provided);
    }

    /** Pure evaluation — no I/O, so the rules can be unit-tested directly. */
    CompletenessResult evaluateAgainst(List<DocumentRequirement> requirements,
                                       Map<DocumentType, Long> provided) {

        // Fail closed on a misconfigured catalog. Without this, allMatch() over
        // empty lists returns true and an application with NO rules would count
        // as complete — vacuous truth letting an unchecked file through.
        if (requirements.isEmpty()) {
            return CompletenessResult.empty();
        }

        List<RequirementStatus> mandatory = new ArrayList<>();
        Map<Integer, List<RequirementStatus>> grouped = new LinkedHashMap<>();

        for (DocumentRequirement r : requirements) {
            int count = provided.getOrDefault(r.getDocType(), 0L).intValue();
            RequirementStatus status = RequirementStatus.of(r, count);

            if (r.isMandatory()) {
                mandatory.add(status);
            } else {
                grouped.computeIfAbsent(r.getAlternativeGroup(), k -> new ArrayList<>())
                        .add(status);
            }
        }

        List<AlternativeGroup> groups = grouped.entrySet().stream()
                .map(e -> new AlternativeGroup(
                        e.getKey(),
                        e.getValue().stream().anyMatch(RequirementStatus::satisfied),
                        e.getValue()))
                .toList();

        boolean complete =
                mandatory.stream().allMatch(RequirementStatus::satisfied)
                        && groups.stream().allMatch(AlternativeGroup::satisfied);

        return new CompletenessResult(complete, mandatory, groups,
                describeMissing(mandatory, groups));
    }

    /** Plain-French explanation of what is still missing. */
    private List<String> describeMissing(List<RequirementStatus> mandatory,
                                         List<AlternativeGroup> groups) {
        List<String> missing = new ArrayList<>();

        for (RequirementStatus r : mandatory) {
            if (!r.satisfied()) {
                int remaining = r.required() - r.provided();
                missing.add(r.required() == 1
                        ? "%s : document requis.".formatted(r.labelFr())
                        : "%s : %d sur %d fournis, il en manque %d."
                            .formatted(r.labelFr(), r.provided(), r.required(), remaining));
            }
        }

        for (AlternativeGroup g : groups) {
            if (!g.satisfied()) {
                String options = g.options().stream()
                        .map(o -> o.required() == 1
                                ? o.labelFr()
                                : "%s (%d)".formatted(o.labelFr(), o.required()))
                        .collect(Collectors.joining(" ou "));
                missing.add("Fournissez au moins l'un des éléments suivants : " + options + ".");
            }
        }

        return missing;
    }
}
