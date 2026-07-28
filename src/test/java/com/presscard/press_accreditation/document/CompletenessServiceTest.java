package com.presscard.press_accreditation.document;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The completeness rules are the most consequential logic in the candidate
 * flow: get them wrong and either incomplete files reach the commission, or
 * complete ones are refused. They are tested PURELY — no Spring, no database,
 * no Docker — by feeding the evaluator requirement rows and document counts
 * directly. Milliseconds, and every branch covered.
 *
 * The three fixtures mirror V2__seed_catalog.sql exactly.
 */

class CompletenessServiceTest {

    private final CompletenessService service = new CompletenessService(null, null);

    /* ── fixtures: build requirement rows without a database ── */

    private DocumentRequirement req(DocumentType type, int min, Integer group) {
        try {
            DocumentRequirement r = new DocumentRequirement();
            set(r, "docType", type);
            set(r, "minCount", min);
            set(r, "alternativeGroup", group);
            set(r, "categoryId", 1L);
            return r;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** International media: CONTRACT(1) AND WORK_LINK(3) — both mandatory. */
    private List<DocumentRequirement> internationalMedia() {
        return List.of(
                req(DocumentType.CONTRACT, 1, null),
                req(DocumentType.WORK_LINK, 3, null));
    }

    /** Freelancer: WORK_CERTIFICATE(1) | WEBSITE(1) | WORK_LINK(3) — group 1. */
    private List<DocumentRequirement> freelancer() {
        return List.of(
                req(DocumentType.WORK_CERTIFICATE, 1, 1),
                req(DocumentType.WEBSITE, 1, 1),
                req(DocumentType.WORK_LINK, 3, 1));
    }

    /* ── mandatory requirements: ALL must be met ── */

    @Test
    void mandatory_allSatisfied_isComplete() {
        var result = service.evaluateAgainst(internationalMedia(),
                Map.of(DocumentType.CONTRACT, 1L, DocumentType.WORK_LINK, 3L));

        assertThat(result.complete()).isTrue();
        assertThat(result.missingFr()).isEmpty();
        assertThat(result.mandatory()).hasSize(2).allMatch(r -> r.satisfied());
    }

    @Test
    void mandatory_oneMissing_isIncomplete_andSaysWhich() {
        var result = service.evaluateAgainst(internationalMedia(),
                Map.of(DocumentType.WORK_LINK, 3L));   // no contract

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFr()).hasSize(1);
        assertThat(result.missingFr().get(0)).contains("Contrat de travail");
    }

    @Test
    void mandatory_partialCount_isIncomplete_andCountsTheGap() {
        var result = service.evaluateAgainst(internationalMedia(),
                Map.of(DocumentType.CONTRACT, 1L, DocumentType.WORK_LINK, 2L));  // 2 of 3

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFr().get(0))
                .contains("2 sur 3")
                .contains("il en manque 1");
    }

    @Test
    void mandatory_extraDocumentsAreFine() {
        var result = service.evaluateAgainst(internationalMedia(),
                Map.of(DocumentType.CONTRACT, 2L, DocumentType.WORK_LINK, 5L));

        assertThat(result.complete()).isTrue();
    }

    /* ── alternative group: ANY ONE option suffices ── */

    @Test
    void alternatives_certificateAlone_satisfiesTheGroup() {
        var result = service.evaluateAgainst(freelancer(),
                Map.of(DocumentType.WORK_CERTIFICATE, 1L));

        assertThat(result.complete()).isTrue();
        assertThat(result.alternativeGroups()).hasSize(1);
        assertThat(result.alternativeGroups().get(0).satisfied()).isTrue();
    }

    @Test
    void alternatives_websiteAlone_satisfiesTheGroup() {
        var result = service.evaluateAgainst(freelancer(),
                Map.of(DocumentType.WEBSITE, 1L));

        assertThat(result.complete()).isTrue();
    }

    @Test
    void alternatives_threeLinksAlone_satisfiesTheGroup() {
        var result = service.evaluateAgainst(freelancer(),
                Map.of(DocumentType.WORK_LINK, 3L));

        assertThat(result.complete()).isTrue();
    }

    @Test
    void alternatives_twoLinksOnly_doesNotSatisfy_theMinimumStillApplies() {
        var result = service.evaluateAgainst(freelancer(),
                Map.of(DocumentType.WORK_LINK, 2L));   // below the minimum of 3

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFr().get(0)).contains("au moins l'un des éléments");
    }

    @Test
    void alternatives_nothingProvided_listsEveryOption() {
        var result = service.evaluateAgainst(freelancer(), Map.of());

        assertThat(result.complete()).isFalse();
        String message = result.missingFr().get(0);
        assertThat(message)
                .contains("Attestation de travail")
                .contains("Site web professionnel")
                .contains("Lien de publication (3)")
                .contains(" ou ");
    }

    @Test
    void alternatives_severalOptionsMet_isStillJustComplete() {
        var result = service.evaluateAgainst(freelancer(),
                Map.of(DocumentType.WORK_CERTIFICATE, 1L, DocumentType.WEBSITE, 1L));

        assertThat(result.complete()).isTrue();
        assertThat(result.alternativeGroups().get(0).options())
                .filteredOn(o -> o.satisfied()).hasSize(2);
    }

    /* ── mixed: mandatory AND a group must both hold ── */

    @Test
    void mixed_groupSatisfiedButMandatoryMissing_isIncomplete() {
        List<DocumentRequirement> mixed = List.of(
                req(DocumentType.CONTRACT, 1, null),      // mandatory
                req(DocumentType.WEBSITE, 1, 1),          // group 1
                req(DocumentType.WORK_LINK, 3, 1));       // group 1

        var result = service.evaluateAgainst(mixed, Map.of(DocumentType.WEBSITE, 1L));

        assertThat(result.complete()).isFalse();
        assertThat(result.missingFr()).hasSize(1);
        assertThat(result.missingFr().get(0)).contains("Contrat de travail");
    }

    @Test
    void noRequirementsConfigured_isNeverComplete() {
        var result = service.evaluateAgainst(List.of(), Map.of());
        assertThat(result.complete()).isFalse();
    }
}
