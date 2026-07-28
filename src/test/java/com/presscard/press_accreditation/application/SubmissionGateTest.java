package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.document.DocumentType;
import com.presscard.press_accreditation.error.SubmissionRefusedException;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.*;
import com.presscard.press_accreditation.user.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The submission gate is where policy meets code, so each of its five
 * conditions gets a test proving it BLOCKS — and one proving a fully valid
 * application passes.
 *
 * The most consequential is deadlinePassed_blocksEvenWhileSessionIsReceiving:
 * that is the "binding deadline" decision, and without a test it would be one
 * refactor away from silently reverting to advisory.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
@ActiveProfiles("test")
class SubmissionGateTest {

    @Autowired ApplicationService applicationService;
    @Autowired SubmissionGate gate;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CandidateProfileRepository profileRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired EntityManager em;

    /* ── fixtures ── */

    private User candidate(boolean emailVerified) {
        User u = User.builder()
                .email("cand-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(UserRole.CANDIDATE)
                .fullName("Test Candidate").phone("22123456")
                .build();
        u.setEmailVerified(emailVerified);
        return userRepository.save(u);
    }

//    private void completeProfile(Long userId) {
//        profileRepository.save(CandidateProfile.builder()
//                .userId(userId)
//                .nni("1234567890")
//                .birthdate(LocalDate.of(1990, 5, 14))
//                .birthplace("Nouakchott")
//                .build());
//    }

    private void completeProfile(Long userId) {
        profileRepository.save(CandidateProfile.builder()
                .userId(userId)
                .nni("1234567890")
                .birthdate(LocalDate.of(1990, 5, 14))
                .birthplace("Nouakchott")
                // Required since V6 — a dossier cannot produce a card without
                // a photograph, so isComplete() demands one.
                .photoPath("photos/1/test-photo.jpg")
                .photoUploadedAt(OffsetDateTime.now())
                .build());
    }

    /** A session in RECEIVING whose deadline is `daysFromNow` away. */
//    private Session session(long daysFromNow) {
//        LocalDate today = LocalDate.now();
//        Session s = Session.builder()
//                .type(SessionType.CANDIDACY)
//                .startDate(today)
//                .totalDays(30)
//                .receivingDays(10).reviewDays(8).correctionDays(7).reclamationDays(5)
//                .receivingEnd(today.plusDays(daysFromNow))
//                .reviewEnd(today.plusDays(daysFromNow + 8))
//                .correctionEnd(today.plusDays(daysFromNow + 15))
//                .reclamationEnd(today.plusDays(daysFromNow + 20))
//                .phaseStartedAt(today)
//                .status(SessionStatus.RECEIVING)
//                .createdBy(1L)
//                .build();
//        return sessionRepository.save(s);
//    }

    /** A session in RECEIVING whose deadline is `daysFromNow` away
     *  (negative = the deadline has already passed). */
    private Session session(long daysFromNow) {
        LocalDate today = LocalDate.now();
        LocalDate receivingEnd = today.plusDays(daysFromNow);
        // The session must have STARTED before its deadline — a past deadline
        // implies a session that opened earlier still.
        LocalDate start = receivingEnd.minusDays(10);

        Session s = Session.builder()
                .type(SessionType.CANDIDACY)
                .startDate(start)
                .totalDays(30)
                .receivingDays(10).reviewDays(8).correctionDays(7).reclamationDays(5)
                .receivingEnd(receivingEnd)
                .reviewEnd(receivingEnd.plusDays(8))
                .correctionEnd(receivingEnd.plusDays(15))
                .reclamationEnd(receivingEnd.plusDays(20))
                .phaseStartedAt(start)
                .status(SessionStatus.RECEIVING)
                .createdBy(1L)
                .build();
        return sessionRepository.save(s);
    }

    private Long firstCategoryId() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM press_categories WHERE code = 'PUBLIC_EMPLOYEE'")
                .getSingleResult()).longValue();
    }

    /** PUBLIC_EMPLOYEE needs exactly one WORK_CERTIFICATE. */
    private void attachRequiredCertificate(Long applicationId) {
        em.createNativeQuery("""
            INSERT INTO application_documents
                (application_id, doc_type, kind, file_path)
            VALUES (:app, 'WORK_CERTIFICATE', 'FILE', '2026/07/1/test.pdf')
            """).setParameter("app", applicationId).executeUpdate();
        em.flush();
    }

    /* ── the happy path ── */

    @Test
    void everythingSatisfied_submissionSucceeds() {
        User user = candidate(true);
        completeProfile(user.getId());
        Session s = session(5);

        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());
        attachRequiredCertificate(app.getId());

        var result = gate.evaluate(app);
        assertThat(result.blockers()).isEmpty();
        assertThat(result.allowed()).isTrue();

        Application submitted = applicationService.submit(app.getId(), user.getId());
        assertThat(submitted.getStatus()).isEqualTo(ApplicationStatus.UNDER_REVIEW);
        assertThat(submitted.getSubmittedAt()).isNotNull();
    }

    /* ── condition 2: the BINDING deadline ── */

    @Test
    void deadlinePassed_blocksEvenWhileSessionIsReceiving() {
        User user = candidate(true);
        completeProfile(user.getId());

        // The session is still open — nobody has clicked "advance" — but the
        // PUBLISHED deadline was yesterday.
        Session s = session(-1);

        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());
        attachRequiredCertificate(app.getId());

        var result = gate.evaluate(app);
        assertThat(result.allowed()).isFalse();
        assertThat(result.blockers())
                .extracting(b -> b.reason())
                .contains(SubmissionGate.Blocker.Reason.DEADLINE_PASSED);

        assertThatThrownBy(() -> applicationService.submit(app.getId(), user.getId()))
                .isInstanceOf(SubmissionRefusedException.class);
    }

    /* ── condition 4: e-mail verification ── */

    @Test
    void unverifiedEmail_blocksSubmission_butNotDrafting() {
        User user = candidate(false);          // NOT verified
        completeProfile(user.getId());
        Session s = session(5);

        // Drafting and attaching are allowed — only submission is gated.
        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());
        attachRequiredCertificate(app.getId());
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.DRAFT);

        var result = gate.evaluate(app);
        assertThat(result.allowed()).isFalse();
        assertThat(result.blockers())
                .extracting(b -> b.reason())
                .contains(SubmissionGate.Blocker.Reason.EMAIL_NOT_VERIFIED);
    }

    /* ── condition 3: the profile ── */

    @Test
    void missingProfile_blocksSubmission() {
        User user = candidate(true);           // no profile created
        Session s = session(5);

        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());
        attachRequiredCertificate(app.getId());

        var result = gate.evaluate(app);
        assertThat(result.allowed()).isFalse();
        assertThat(result.blockers())
                .extracting(b -> b.reason())
                .contains(SubmissionGate.Blocker.Reason.PROFILE_INCOMPLETE);
    }

    /* ── condition 5: the documents ── */

    @Test
    void missingDocuments_blocksSubmission_andExplainsWhat() {
        User user = candidate(true);
        completeProfile(user.getId());
        Session s = session(5);

        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());
        // nothing attached

        var result = gate.evaluate(app);
        assertThat(result.allowed()).isFalse();
        assertThat(result.blockers())
                .extracting(b -> b.reason())
                .contains(SubmissionGate.Blocker.Reason.DOCUMENTS_INCOMPLETE);
        assertThat(result.completeness().missingFr().get(0))
                .contains("Attestation de travail");
    }

    /* ── every failure at once ── */

    @Test
    void multipleProblems_areAllReportedTogether() {
        User user = candidate(false);          // unverified
        Session s = session(5);                // no profile, no documents

        Application app = applicationService.startOrResume(
                user.getId(), s.getId(), firstCategoryId());

        var result = gate.evaluate(app);
        assertThat(result.allowed()).isFalse();
        // The candidate should be able to fix everything in one pass.
        assertThat(result.blockers()).hasSize(3);
    }

    /* ── one application per candidate per session ── */

    @Test
    void startingTwice_resumesTheSameDraft() {
        User user = candidate(true);
        Session s = session(5);
        Long categoryId = firstCategoryId();

        Application first = applicationService.startOrResume(user.getId(), s.getId(), categoryId);
        Application second = applicationService.startOrResume(user.getId(), s.getId(), categoryId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(applicationRepository.findByCandidateIdAndSessionId(
                user.getId(), s.getId())).isPresent();
    }
}
