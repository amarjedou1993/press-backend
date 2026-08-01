package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.error.*;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The review workflow decides people's professional accreditation, so each
 * rule that protects that decision gets a test — especially the legal one.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ReviewServiceTest {

    @Autowired ReviewService reviewService;
    @Autowired ApplicationService applicationService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ReviewDecisionRepository decisionRepository;
    @Autowired UserRepository userRepository;
    @Autowired CandidateProfileRepository profileRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired EntityManager em;

    /* ── fixtures ── */

    private User user(UserRole role) {
        User u = User.builder()
                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(role)
                .fullName(role == UserRole.REVIEWER ? "Membre Commission" : "Candidat Test")
                .phone("22123456")
                .build();
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

    private Session openSession() {
        LocalDate today = LocalDate.now();
        return sessionRepository.save(Session.builder()
                .type(SessionType.CANDIDACY).startDate(today).totalDays(30)
                .receivingDays(10).reviewDays(8).correctionDays(7).reclamationDays(5)
                .receivingEnd(today.plusDays(10)).reviewEnd(today.plusDays(18))
                .correctionEnd(today.plusDays(25)).reclamationEnd(today.plusDays(30))
                .phaseStartedAt(today).status(SessionStatus.RECEIVING).createdBy(1L)
                .build());
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

    /** A submitted application, ready for the commission. */
    private Application submitted() {
        User candidate = user(UserRole.CANDIDATE);
        profileRepository.save(CandidateProfile.builder()
                .userId(candidate.getId()).nni("1234567890")
                .birthdate(LocalDate.of(1990, 5, 14)).birthplace("Nouakchott")
                .photoPath("photos/1/x.jpg").photoUploadedAt(OffsetDateTime.now())
                .build());

        Session session = openSession();
        Application app = applicationService.startOrResume(
                candidate.getId(), session.getId(), categoryId());

        // Required since V12 — التخصص and المؤسسة are printed on the card, so
        // the submission gate refuses a dossier that cannot produce one.
        app.setSpecialisationId(journalistSpecialisationId());
        app.setInstitution("Mauri News");
        applicationRepository.save(app);
        em.flush();

        em.createNativeQuery("""
            INSERT INTO application_documents (application_id, doc_type, kind, file_path)
            VALUES (:app, 'WORK_CERTIFICATE', 'FILE', '2026/07/1/test.pdf')
            """).setParameter("app", app.getId()).executeUpdate();
        em.flush();

        return applicationService.submit(app.getId(), candidate.getId());
    }

    /* ══ claiming ══════════════════════════════════════════ */

    @Test
    void claiming_takesTheDossierOutOfThePool() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);

        assertThat(reviewService.pool(reviewer.getId()))
                .extracting(Application::getId).contains(app.getId());

        reviewService.claim(app.getId(), reviewer.getId());

        assertThat(reviewService.pool(reviewer.getId()))
                .extracting(Application::getId).doesNotContain(app.getId());
        assertThat(reviewService.myClaims(reviewer.getId()))
                .extracting(Application::getId).contains(app.getId());
    }

    @Test
    void aSecondReviewerCannotClaimTheSameDossier() {
        Application app = submitted();
        User first = user(UserRole.REVIEWER);
        User second = user(UserRole.REVIEWER);

        reviewService.claim(app.getId(), first.getId());

        // Without the conditional UPDATE, this would silently steal the file
        // and two members would examine it in parallel.
        assertThatThrownBy(() -> reviewService.claim(app.getId(), second.getId()))
                .isInstanceOf(AlreadyClaimedException.class);
    }

    @Test
    void releasing_putsItBackInThePool() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);

        reviewService.claim(app.getId(), reviewer.getId());
        reviewService.release(app.getId(), reviewer.getId(), false);

        assertThat(reviewService.pool(reviewer.getId()))
                .extracting(Application::getId).contains(app.getId());
    }

    @Test
    void aReviewerCannotReleaseSomeoneElsesClaim_butAnAdminCan() {
        Application app = submitted();
        User holder = user(UserRole.REVIEWER);
        User other = user(UserRole.REVIEWER);

        reviewService.claim(app.getId(), holder.getId());

        assertThatThrownBy(() -> reviewService.release(app.getId(), other.getId(), false))
                .isInstanceOf(NotYourClaimException.class);

        // An admin force-release: a reviewer's absence must not freeze a file.
        reviewService.release(app.getId(), other.getId(), true);
        assertThat(reviewService.pool(other.getId()))
                .extracting(Application::getId).contains(app.getId());
    }

    /* ══ deciding requires the claim ═══════════════════════ */

    @Test
    void decidingWithoutClaiming_isRefused() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);

        assertThatThrownBy(() -> reviewService.approve(app.getId(), reviewer.getId(), null))
                .isInstanceOf(NotYourClaimException.class);
    }

    @Test
    void decidingOnAnotherReviewersClaim_isRefused() {
        Application app = submitted();
        User holder = user(UserRole.REVIEWER);
        User other = user(UserRole.REVIEWER);

        reviewService.claim(app.getId(), holder.getId());

        assertThatThrownBy(() -> reviewService.approve(app.getId(), other.getId(), null))
                .isInstanceOf(NotYourClaimException.class);
    }

    /* ══ approve ═══════════════════════════════════════════ */

    @Test
    void approving_acceptsTheApplication_andRecordsTheDecision() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        reviewService.approve(app.getId(), reviewer.getId(), "Dossier conforme.");

        Application reloaded = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);

        List<ReviewDecision> decisions =
                decisionRepository.findByApplicationIdOrderByCreatedAtAsc(app.getId());
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecision()).isEqualTo(DecisionType.APPROVE);
        assertThat(decisions.get(0).getRound()).isEqualTo(ReviewRound.INITIAL);
        assertThat(decisions.get(0).getReviewerId()).isEqualTo(reviewer.getId());
    }

    /* ══ reject ════════════════════════════════════════════ */

    @Test
    void rejecting_requiresAJustification() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        // The candidate has an objection right; it is meaningless if they do
        // not know what they are objecting to.
        assertThatThrownBy(() -> reviewService.reject(
                app.getId(), reviewer.getId(), RejectionGround.INELIGIBLE, "  "))
                .isInstanceOf(JustificationRequiredException.class);
    }

    @Test
    void rejectingOnASubstantiveGround_needsNoPriorCorrection() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        reviewService.reject(app.getId(), reviewer.getId(),
                RejectionGround.INELIGIBLE,
                "Le candidat n'exerce pas une activité journalistique régulière.");

        Application reloaded = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(decisionRepository.findByApplicationIdOrderByCreatedAtAsc(app.getId()))
                .first()
                .satisfies(d -> assertThat(d.getRejectionGround())
                        .isEqualTo(RejectionGround.INELIGIBLE));
    }

    /**
     * THE LEGAL RULE. An authority may not reject a file as incomplete
     * without first inviting the applicant to complete it. This test is the
     * guard against a future refactor quietly removing that protection.
     */
    @Test
    void rejectingAsIncomplete_isRefusedWhenNoCorrectionWasEverRequested() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        assertThatThrownBy(() -> reviewService.reject(
                app.getId(), reviewer.getId(),
                RejectionGround.INCOMPLETE_FILE,
                "Pièces manquantes."))
                .isInstanceOf(CorrectionRequiredFirstException.class)
                .hasMessageContaining("sans qu'une correction ait d'abord été demandée");

        // …and the dossier is untouched: a refused decision changes nothing.
        assertThat(applicationRepository.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.UNDER_REVIEW);
    }

    /* ══ request correction ════════════════════════════════ */

    @Test
    void requestingCorrection_flagsTheDocument_andReturnsTheFileToTheCandidate() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        Long documentId = ((Number) em.createNativeQuery(
                "SELECT id FROM application_documents WHERE application_id = :app")
                .setParameter("app", app.getId()).getSingleResult()).longValue();

        reviewService.requestCorrection(app.getId(), reviewer.getId(),
                "L'attestation fournie est illisible.",
                List.of(new ReviewService.DocumentFlag(documentId, "Scan illisible — refaire.")),
                false, null);

        Application reloaded = applicationRepository.findById(app.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.CORRECTION_REQUESTED);
        assertThat(reloaded.getCorrectionCount()).isEqualTo(1);
        // The file is back with the candidate, so the claim ends with it.
        assertThat(reloaded.getClaimedBy()).isNull();
    }

    @Test
    void requestingCorrection_withNothingFlagged_isRefused() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        // "Your file is incomplete" with nothing named does not tell the
        // candidate what to do.
        assertThatThrownBy(() -> reviewService.requestCorrection(
                app.getId(), reviewer.getId(), "À corriger.", List.of(), false, null))
                .isInstanceOf(JustificationRequiredException.class);
    }

    @Test
    void afterACorrectionRound_rejectingAsIncompleteBecomesPermitted() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        Long documentId = ((Number) em.createNativeQuery(
                "SELECT id FROM application_documents WHERE application_id = :app")
                .setParameter("app", app.getId()).getSingleResult()).longValue();

        reviewService.requestCorrection(app.getId(), reviewer.getId(), "Illisible.",
                List.of(new ReviewService.DocumentFlag(documentId, "Refaire le scan.")),
                false, null);

        // The candidate answered; the file returns for final examination.
        Application app2 = applicationRepository.findById(app.getId()).orElseThrow();
        applicationService.transition(app2, ApplicationStatus.UNDER_FINAL_REVIEW,
                app2.getCandidateId(), null);
        reviewService.claim(app.getId(), reviewer.getId());

        // The duty has been discharged, so the ground is now available.
        reviewService.reject(app.getId(), reviewer.getId(),
                RejectionGround.INCOMPLETE_FILE,
                "Les pièces corrigées ne satisfont toujours pas les exigences.");

        assertThat(applicationRepository.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
    }

    /* ══ one decision per round ════════════════════════════ */

    @Test
    void aDossierCannotBeDecidedTwiceInTheSameRound() {
        Application app = submitted();
        User reviewer = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), reviewer.getId());

        reviewService.approve(app.getId(), reviewer.getId(), null);

        // ACCEPTED is no longer awaiting review, so the second attempt is
        // stopped before it can reach the UNIQUE constraint.
        assertThatThrownBy(() -> reviewService.approve(app.getId(), reviewer.getId(), null))
                .isInstanceOf(NotAwaitingReviewException.class);
    }
}
