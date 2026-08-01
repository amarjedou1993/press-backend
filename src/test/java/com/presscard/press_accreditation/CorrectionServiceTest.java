package com.presscard.press_accreditation;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.ApplicationDocumentRepository;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.review.ReviewService;
import com.presscard.press_accreditation.session.*;
import com.presscard.press_accreditation.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The correction round is the candidate's one chance to answer the
 * commission. Each rule that protects it — or protects the commission from
 * it — gets a test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class CorrectionServiceTest {

    @Autowired
    CorrectionService correctionService;
    @Autowired ReviewService reviewService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    ApplicationRepository applicationRepository;
    @Autowired ApplicationDocumentRepository documentRepository;
    @Autowired CandidateProfileRepository profileRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    /* ── fixtures ── */

    private User user(UserRole role) {
        User u = User.builder()
                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(role).fullName("Test User").phone("22123456")
                .build();
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

    /**
     * A session OPEN for submission.
     *
     * The fixture has to follow the real lifecycle: a dossier can only be
     * created and submitted while the session is RECEIVING. It reaches the
     * correction phase afterwards — which is what advanceToCorrection does.
     */
    private Session openSession() {
        LocalDate today = LocalDate.now();
        return sessionRepository.save(Session.builder()
                .type(SessionType.CANDIDACY).startDate(today).totalDays(40)
                .receivingDays(10).reviewDays(8).correctionDays(7).reclamationDays(5)
                .receivingEnd(today.plusDays(10)).reviewEnd(today.plusDays(18))
                .correctionEnd(today.plusDays(25)).reclamationEnd(today.plusDays(30))
                .phaseStartedAt(today).status(SessionStatus.RECEIVING)
                .createdBy(1L).build());
    }

    /**
     * Move the session into its correction phase, with the deadline placed
     * relative to today.
     *
     * Every boundary is derived from correctionEnd so the calendar stays
     * ordered whether the deadline is days away or already past —
     * session_phases_ordered would otherwise reject the row, which is the
     * constraint doing its job on a bad fixture.
     */
    private Session advanceToCorrection(Session session, int correctionDaysFromNow) {
        LocalDate correctionEnd = LocalDate.now().plusDays(correctionDaysFromNow);

        session.setStartDate(correctionEnd.minusDays(30));
        session.setReceivingEnd(correctionEnd.minusDays(20));
        session.setReviewEnd(correctionEnd.minusDays(10));
        session.setCorrectionEnd(correctionEnd);
        session.setReclamationEnd(correctionEnd.plusDays(5));
        session.setStatus(SessionStatus.CORRECTION);
        session.setPhaseStartedAt(correctionEnd.minusDays(10));

        return sessionRepository.save(session);
    }

    private Long categoryId() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM press_categories WHERE code = 'PUBLIC_EMPLOYEE'")
                .getSingleResult()).longValue();
    }

    private Long journalistSpecialisationId() {
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM specialisations WHERE code = 'JOURNALIST'")
                .getSingleResult()).longValue();
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "corrected.pdf", "application/pdf",
                "%PDF-1.4 corrected".getBytes());
    }

    /** A dossier sitting in CORRECTION_REQUESTED with one flagged document. */
    private record Fixture(Application application, Long documentId, User candidate) {}

    private Fixture inCorrection(int correctionDaysFromNow) {
        User candidate = user(UserRole.CANDIDATE);
        profileRepository.save(CandidateProfile.builder()
                .userId(candidate.getId()).nni("1234567890")
                .birthdate(LocalDate.of(1990, 5, 14)).birthplace("Nouakchott")
                .photoPath("photos/1/x.jpg").photoUploadedAt(OffsetDateTime.now().minusDays(30))
                .build());

        // 1. submit while the session is still open
        Session s = openSession();
        Application app = applicationService.startOrResume(
                candidate.getId(), s.getId(), categoryId());

        // Required since V12 — التخصص and المؤسسة are printed on the card, so
        // the submission gate refuses a dossier that cannot produce one.
        app.setSpecialisationId(journalistSpecialisationId());
        app.setInstitution("Mauri News");
        applicationRepository.save(app);
        em.flush();

        em.createNativeQuery("""
            INSERT INTO application_documents (application_id, doc_type, kind, file_path, version)
            VALUES (:app, 'WORK_CERTIFICATE', 'FILE', '2026/07/1/original.pdf', 1)
            """).setParameter("app", app.getId()).executeUpdate();
        em.flush();

        applicationService.submit(app.getId(), candidate.getId());

        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        Long documentId = ((Number) em.createNativeQuery(
                "SELECT id FROM application_documents WHERE application_id = :app")
                .setParameter("app", app.getId()).getSingleResult()).longValue();

        reviewService.requestCorrection(app.getId(), reviewer.getId(),
                "L'attestation est illisible.",
                List.of(new ReviewService.DocumentFlag(documentId, "Scan illisible.")),
                false, null);
        em.flush();

        // 2. only NOW does the session reach its correction phase, with the
        //    deadline the test wants
        advanceToCorrection(s, correctionDaysFromNow);
        em.flush();
        em.clear();

        return new Fixture(
                applicationRepository.findById(app.getId()).orElseThrow(),
                documentId, candidate);
    }

    /* ══ replacing ══════════════════════════════════════════ */

    @Test
    void replacing_keepsTheOriginal_andSupersedesIt() {
        Fixture f = inCorrection(5);

        ApplicationDocument replacement = correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), f.candidate().getId());

        // The original SURVIVES: it is evidence of what the first decision
        // was taken on.
        ApplicationDocument original = documentRepository.findById(f.documentId()).orElseThrow();
        assertThat(original.getSupersededAt()).isNotNull();
        assertThat(original.getSupersededBy()).isEqualTo(replacement.getId());

        assertThat(replacement.getVersion()).isEqualTo(2);
        assertThat(replacement.isNeedsCorrection()).isFalse();
        assertThat(replacement.getSupersededAt()).isNull();       // this one is current
    }

    @Test
    void onlyCurrentDocumentsCount_soAReplacementIsNotCountedTwice() {
        Fixture f = inCorrection(5);
        correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), f.candidate().getId());

        List<ApplicationDocument> current =
                documentRepository.findCurrentByApplicationId(f.application().getId());
        List<ApplicationDocument> all =
                documentRepository.findByApplicationIdOrderByUploadedAtAsc(f.application().getId());

        // Two rows exist; only one is current. Counting both would credit the
        // candidate twice for one piece of evidence.
        assertThat(all).hasSize(2);
        assertThat(current).hasSize(1);
        assertThat(current.get(0).getVersion()).isEqualTo(2);
    }

    @Test
    void replacingSomethingTheCommissionDidNotFlag_isRefused() {
        Fixture f = inCorrection(5);

        // A second, unflagged document.
        em.createNativeQuery("""
            INSERT INTO application_documents (application_id, doc_type, kind, file_path, version)
            VALUES (:app, 'CONTRACT', 'FILE', '2026/07/1/contract.pdf', 1)
            """).setParameter("app", f.application().getId()).executeUpdate();
        em.flush();

        Long unflagged = ((Number) em.createNativeQuery("""
            SELECT id FROM application_documents
            WHERE application_id = :app AND doc_type = 'CONTRACT'
            """).setParameter("app", f.application().getId()).getSingleResult()).longValue();

        // A correction round answers what was asked; it is not a chance to
        // swap evidence the reviewer already accepted.
        assertThatThrownBy(() -> correctionService.replaceDocument(
                f.application().getId(), unflagged, pdf(), f.candidate().getId()))
                .isInstanceOf(NotCorrectableException.class)
                .hasMessageContaining("n'a pas été signalée");
    }

    @Test
    void replacingAfterTheDeadline_isRefused() {
        Fixture f = inCorrection(-1);          // the window closed yesterday

        assertThatThrownBy(() -> correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), f.candidate().getId()))
                .isInstanceOf(NotCorrectableException.class)
                .hasMessageContaining("délai de correction est expiré");
    }

    @Test
    void anotherCandidatesDossier_isInvisible() {
        Fixture f = inCorrection(5);
        User stranger = user(UserRole.CANDIDATE);

        // 404, not 403: it does not exist for them.
        assertThatThrownBy(() -> correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), stranger.getId()))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    /* ══ resubmission ═══════════════════════════════════════ */

    @Test
    void resubmitting_withEverythingAnswered_returnsItForFinalReview() {
        Fixture f = inCorrection(5);
        correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), f.candidate().getId());

        correctionService.resubmit(f.application().getId(), f.candidate().getId());

        Application reloaded = applicationRepository
                .findById(f.application().getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.UNDER_FINAL_REVIEW);
    }

    @Test
    void resubmitting_withSomethingStillFlagged_isRefused_andNamesIt() {
        Fixture f = inCorrection(5);

        // Nothing replaced yet.
        assertThatThrownBy(() -> correctionService.resubmit(
                f.application().getId(), f.candidate().getId()))
                .isInstanceOf(CorrectionIncompleteException.class)
                .hasMessageContaining("Attestation");
    }

    @Test
    void theStateObject_explainsWhatRemains() {
        Fixture f = inCorrection(5);

        CorrectionService.CorrectionState before = correctionService.state(
                f.application().getId(), f.candidate().getId());

        assertThat(before.inCorrection()).isTrue();
        assertThat(before.readyToResubmit()).isFalse();
        assertThat(before.remainingFr()).isNotEmpty();
        assertThat(before.documents()).hasSize(1);
        assertThat(before.documents().get(0).answered()).isFalse();

        correctionService.replaceDocument(
                f.application().getId(), f.documentId(), pdf(), f.candidate().getId());

        CorrectionService.CorrectionState after = correctionService.state(
                f.application().getId(), f.candidate().getId());

        assertThat(after.readyToResubmit()).isTrue();
        assertThat(after.remainingFr()).isEmpty();
        assertThat(after.documents().get(0).answered()).isTrue();
    }

    @Test
    void aDossierNotInCorrection_cannotBeCorrected() {
        User candidate = user(UserRole.CANDIDATE);
        Session s = openSession();
        Application draft = applicationService.startOrResume(
                candidate.getId(), s.getId(), categoryId());

        assertThatThrownBy(() -> correctionService.resubmit(
                draft.getId(), candidate.getId()))
                .isInstanceOf(NotCorrectableException.class);
    }
}
