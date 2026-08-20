//package com.presscard.press_accreditation.document;
//
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.util.*;
//import java.util.stream.Collectors;
//
//@Service
//public class CompletenessService {
//
//    /* ── result shapes ───────────────────────────────────────── */
//
//    /** One requirement and how the candidate currently stands against it. */
//    public record RequirementStatus(
//            DocumentType docType,
//            String labelFr,
//            String labelAr,
//            boolean isFile,
//            int required,
//            int provided,
//            boolean satisfied
//    ) {
//        static RequirementStatus of(DocumentRequirement r, int provided) {
//            return new RequirementStatus(
//                    r.getDocType(), r.getDocType().labelFr(), r.getDocType().labelAr(),
//                    r.getDocType().isFile(), r.getMinCount(), provided,
//                    provided >= r.getMinCount());
//        }
//    }
//
//    /** A set of interchangeable options; satisfying any ONE satisfies it. */
//    public record AlternativeGroup(
//            int groupNumber,
//            boolean satisfied,
//            List<RequirementStatus> options
//    ) {}
//
//    /** The whole picture: the verdict plus everything needed to explain it. */
//    public record CompletenessResult(
//            boolean complete,
//            List<RequirementStatus> mandatory,
//            List<AlternativeGroup> alternativeGroups,
//            List<String> missingFr
//    ) {
//        public static CompletenessResult empty() {
//            return new CompletenessResult(false, List.of(), List.of(),
//                    List.of("Aucune exigence documentaire n'est définie pour cette catégorie."));
//        }
//    }
//
//    private final DocumentRequirementRepository requirementRepository;
//    private final ApplicationDocumentRepository documentRepository;
//
//    public CompletenessService(DocumentRequirementRepository requirementRepository,
//                               ApplicationDocumentRepository documentRepository) {
//        this.requirementRepository = requirementRepository;
//        this.documentRepository = documentRepository;
//    }
//
//    /** Evaluate an application against its category's rules. */
//    @Transactional(readOnly = true)
//    public CompletenessResult evaluate(Long applicationId, Long categoryId) {
//        List<DocumentRequirement> requirements =
//                requirementRepository.findByCategoryId(categoryId);
//
//        // How many valid documents of each type the candidate has provided.
//        // A document flagged needs_correction does NOT count: it is precisely
//        // the piece the commission asked to be replaced.
//        Map<DocumentType, Long> provided = documentRepository
//                .findCurrentByApplicationId(applicationId).stream()
//                .collect(Collectors.groupingBy(ApplicationDocument::getDocType,
//                        Collectors.counting()));
//
//        return evaluateAgainst(requirements, provided);
//    }
//
//    /** Pure evaluation — no I/O, so the rules can be unit-tested directly. */
//    CompletenessResult evaluateAgainst(List<DocumentRequirement> requirements,
//                                       Map<DocumentType, Long> provided) {
//
//        // Fail closed on a misconfigured catalog. Without this, allMatch() over
//        // empty lists returns true and an application with NO rules would count
//        // as complete — vacuous truth letting an unchecked file through.
//        if (requirements.isEmpty()) {
//            return CompletenessResult.empty();
//        }
//
//        List<RequirementStatus> mandatory = new ArrayList<>();
//        Map<Integer, List<RequirementStatus>> grouped = new LinkedHashMap<>();
//
//        for (DocumentRequirement r : requirements) {
//            int count = provided.getOrDefault(r.getDocType(), 0L).intValue();
//            RequirementStatus status = RequirementStatus.of(r, count);
//
//            if (r.isMandatory()) {
//                mandatory.add(status);
//            } else {
//                grouped.computeIfAbsent(r.getAlternativeGroup(), k -> new ArrayList<>())
//                        .add(status);
//            }
//        }
//
//        List<AlternativeGroup> groups = grouped.entrySet().stream()
//                .map(e -> new AlternativeGroup(
//                        e.getKey(),
//                        e.getValue().stream().anyMatch(RequirementStatus::satisfied),
//                        e.getValue()))
//                .toList();
//
//        boolean complete =
//                mandatory.stream().allMatch(RequirementStatus::satisfied)
//                        && groups.stream().allMatch(AlternativeGroup::satisfied);
//
//        return new CompletenessResult(complete, mandatory, groups,
//                describeMissing(mandatory, groups));
//    }
//
//    /** Plain-French explanation of what is still missing. */
//    private List<String> describeMissing(List<RequirementStatus> mandatory,
//                                         List<AlternativeGroup> groups) {
//        List<String> missing = new ArrayList<>();
//
//        for (RequirementStatus r : mandatory) {
//            if (!r.satisfied()) {
//                int remaining = r.required() - r.provided();
//                missing.add(r.required() == 1
//                        ? "%s : document requis.".formatted(r.labelFr())
//                        : "%s : %d sur %d fournis, il en manque %d."
//                            .formatted(r.labelFr(), r.provided(), r.required(), remaining));
//            }
//        }
//
//        for (AlternativeGroup g : groups) {
//            if (!g.satisfied()) {
//                String options = g.options().stream()
//                        .map(o -> o.required() == 1
//                                ? o.labelFr()
//                                : "%s (%d)".formatted(o.labelFr(), o.required()))
//                        .collect(Collectors.joining(" ou "));
//                missing.add("Fournissez au moins l'un des éléments suivants : " + options + ".");
//            }
//        }
//
//        return missing;
//    }
//}

package com.presscard.press_accreditation.document;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Whether a dossier carries the pieces its category requires.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE STRUCTURED RESULT IS THE ANSWER; THE SENTENCES ARE A CONVENIENCE.
 *
 * `mandatory` and `alternativeGroups` already carry everything a screen needs
 * to explain itself — each requirement's two labels, how many are required,
 * how many are provided, and whether it is satisfied. THE CHECKLIST SHOULD
 * RENDER FROM THOSE, because a list that names each missing piece beside a
 * counter beats a paragraph that strings them together.
 *
 * `missingFr` and `missingAr` exist for LOGS, E-MAILS and the composed
 * blocker message. They are derived, not authoritative.
 * ───────────────────────────────────────────────────────────────────────
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
            List<String> missingFr,
            List<String> missingAr
    ) {
        public static CompletenessResult empty() {
            return new CompletenessResult(false, List.of(), List.of(),
                    List.of("Aucune exigence documentaire n'est définie pour cette catégorie."),
                    List.of("لم تحدد أي وثائق مطلوبة لهذه الفئة."));
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

        // How many valid documents of each type the candidate has provided.
        // A document flagged needs_correction does NOT count: it is precisely
        // the piece the commission asked to be replaced.
        Map<DocumentType, Long> provided = documentRepository
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
                describeMissing(mandatory, groups, Phrasing.FRENCH),
                describeMissing(mandatory, groups, Phrasing.ARABIC));
    }

    /* ══════════════════════════════════════════════════════════════
       THE WORDING, ONCE PER LANGUAGE

       ⚠️ THE COUNTS ARE PHRASED SO PLURALS NEVER ARISE.

       Arabic has six plural forms and String.format has none of them:
       "3 documents" and "11 documents" do not agree the same way, and a
       template with a bare %d is wrong for most numbers.

       So the shortfall is stated as a RATIO — "requis 3, fournis 1" —
       which needs no agreement in either language. The screen, which has
       ICU plurals available, can phrase it properly from the structured
       fields; this string only has to be correct.
       ══════════════════════════════════════════════════════════════ */
    private enum Phrasing {
        FRENCH(
                RequirementStatus::labelFr,
                "%s : document requis.",
                "%s : %d fourni%s sur %d requis.",
                "Fournissez au moins l'un des éléments suivants : %s.",
                " ou ",
                "%s (%d)"),
        ARABIC(
                RequirementStatus::labelAr,
                "%s: وثيقة مطلوبة.",
                "%s: المقدَّم %d من أصل %d مطلوبة.",
                "قدّم واحدًا على الأقل مما يلي: %s.",
                " أو ",
                "%s (%d)");

        final Function<RequirementStatus, String> label;
        final String single;
        final String shortfall;
        final String anyOf;
        final String or;
        final String withCount;

        Phrasing(Function<RequirementStatus, String> label, String single,
                 String shortfall, String anyOf, String or, String withCount) {
            this.label = label;
            this.single = single;
            this.shortfall = shortfall;
            this.anyOf = anyOf;
            this.or = or;
            this.withCount = withCount;
        }
    }

    /** What is still missing, in one language. */
    private List<String> describeMissing(List<RequirementStatus> mandatory,
                                         List<AlternativeGroup> groups,
                                         Phrasing p) {
        List<String> missing = new ArrayList<>();

        for (RequirementStatus r : mandatory) {
            if (!r.satisfied()) {
                String label = p.label.apply(r);
                if (r.required() == 1) {
                    missing.add(p.single.formatted(label));
                } else if (p == Phrasing.FRENCH) {
                    // The French plural agreement on "fourni" — the one place
                    // it is needed, and only because French has just two forms.
                    missing.add(p.shortfall.formatted(
                            label, r.provided(), r.provided() > 1 ? "s" : "", r.required()));
                } else {
                    missing.add(p.shortfall.formatted(label, r.provided(), r.required()));
                }
            }
        }

        for (AlternativeGroup g : groups) {
            if (!g.satisfied()) {
                String options = g.options().stream()
                        .map(o -> o.required() == 1
                                ? p.label.apply(o)
                                : p.withCount.formatted(p.label.apply(o), o.required()))
                        .collect(Collectors.joining(p.or));
                missing.add(p.anyOf.formatted(options));
            }
        }

        return missing;
    }
}
