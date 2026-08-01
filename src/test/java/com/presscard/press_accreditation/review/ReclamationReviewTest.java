package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.error.NotYourClaimException;
import com.presscard.press_accreditation.objection.ObjectionReasonRepository;
import com.presscard.press_accreditation.objection.ObjectionService;
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
 * V1.3 §J — a reclamation is examined by someone OTHER than the author of the
 * decision it contests.
 *
 * The rule is enforced in three places, and each is tested here: the pool
 * hides it, the claim refuses it, and the database trigger rejects the
 * decision row outright. Three layers because this is the guarantee that
 * makes the objection right meaningful rather than decorative.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ReclamationReviewTest {

    @Autowired ReviewService reviewService;
    @Autowired ObjectionService objectionService;
    @Autowired ObjectionReasonRepository reasonRepository;
    @Autowired ApplicationService applicationService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired CandidateProfileRepository profileRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    private User user(UserRole role) {
        User u = User.builder()
                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(role).fullName("Test " + role.name())
                .phone("22123456").build();
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

    private record Contested(Application application, User rejecter, User other) {}

    private Long journalistSpecialisationId() {
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM specialisations WHERE code = 'JOURNALIST'")
                .getSingleResult()).longValue();
    }

    /** A rejected dossier with an objection filed, awaiting a second reviewer. */
    private Contested contested() {
        User candidate = user(UserRole.CANDIDATE);
        profileRepository.save(CandidateProfile.builder()
                .userId(candidate.getId()).nni("1234567890")
                .birthdate(LocalDate.of(1990, 5, 14)).birthplace("Nouakchott")
                .photoPath("photos/1/x.jpg").photoUploadedAt(OffsetDateTime.now())
                .build());

        LocalDate today = LocalDate.now();
        Session s = sessionRepository.save(Session.builder()
                .type(SessionType.CANDIDACY).startDate(today).totalDays(40)
                .receivingDays(10).reviewDays(8).correctionDays(7).reclamationDays(5)
                .receivingEnd(today.plusDays(10)).reviewEnd(today.plusDays(18))
                .correctionEnd(today.plusDays(25)).reclamationEnd(today.plusDays(30))
                .phaseStartedAt(today).status(SessionStatus.RECEIVING)
                .createdBy(1L).build());

        Long categoryId = ((Number) em.createNativeQuery(
                "SELECT id FROM press_categories WHERE code = 'PUBLIC_EMPLOYEE'")
                .getSingleResult()).longValue();

        Application app = applicationService.startOrResume(
                candidate.getId(), s.getId(), categoryId);
        em.createNativeQuery("""
            INSERT INTO application_documents (application_id, doc_type, kind, file_path, version)
            VALUES (:app, 'WORK_CERTIFICATE', 'FILE', '2026/07/1/x.pdf', 1)
            """).setParameter("app", app.getId()).executeUpdate();
        em.flush();

        // Required since V12 — التخصص and المؤسسة are printed on the card, so
        // the submission gate refuses a dossier that cannot produce one.
        app.setSpecialisationId(journalistSpecialisationId());
        app.setInstitution("Mauri News");
        applicationRepository.save(app);
        em.flush();

        applicationService.submit(app.getId(), candidate.getId());

        User rejecter = user(UserRole.REVIEWER);
        User other = user(UserRole.REVIEWER);

        reviewService.claim(app.getId(), rejecter.getId());
        reviewService.reject(app.getId(), rejecter.getId(),
                RejectionGround.INELIGIBLE, "Activité journalistique non établie.");
        em.flush();

        // reclamation phase
        LocalDate end = today.plusDays(5);
        s.setStartDate(end.minusDays(35));
        s.setReceivingEnd(end.minusDays(25));
        s.setReviewEnd(end.minusDays(17));
        s.setCorrectionEnd(end.minusDays(10));
        s.setReclamationEnd(end);
        s.setStatus(SessionStatus.RECLAMATION);
        sessionRepository.save(s);
        em.flush();

        objectionService.file(app.getId(), candidate.getId(),
                reasonRepository.findByCode("MATERIAL_ERROR").orElseThrow().getId(),
                "La commission n'a pas tenu compte de mon attestation de travail.");
        em.flush();
        em.clear();

        return new Contested(
                applicationRepository.findById(app.getId()).orElseThrow(),
                rejecter, other);
    }

    /* ══ layer 1: the pool hides it ════════════════════════ */

    @Test
    void theRejecterDoesNotSeeTheReclamationInTheirPool() {
        Contested c = contested();

        assertThat(reviewService.pool(c.rejecter().getId()))
                .extracting(Application::getId)
                .doesNotContain(c.application().getId());

        // …but another member does.
        assertThat(reviewService.pool(c.other().getId()))
                .extracting(Application::getId)
                .contains(c.application().getId());
    }

    /* ══ layer 2: the claim refuses it ═════════════════════ */

    @Test
    void theRejecterCannotClaimTheReclamation() {
        Contested c = contested();

        assertThatThrownBy(() ->
                reviewService.claim(c.application().getId(), c.rejecter().getId()))
                .isInstanceOf(NotYourClaimException.class)
                .hasMessageContaining("autre membre");
    }

    /* ══ the second reviewer's two outcomes ════════════════ */

    @Test
    void aSecondReviewerMayOverturnTheRejection() {
        Contested c = contested();

        reviewService.claim(c.application().getId(), c.other().getId());
        reviewService.approve(c.application().getId(), c.other().getId(),
                "Après réexamen, l'attestation produite établit l'activité.");

        assertThat(applicationRepository.findById(c.application().getId())
                .orElseThrow().getStatus()).isEqualTo(ApplicationStatus.ACCEPTED);
    }

    @Test
    void aRejectionOnReclamation_isFINAL_notMerelyRejected() {
        Contested c = contested();

        reviewService.claim(c.application().getId(), c.other().getId());
        reviewService.reject(c.application().getId(), c.other().getId(),
                RejectionGround.INELIGIBLE,
                "Le réexamen confirme que les conditions ne sont pas réunies.");

        // FINAL_REJECTION, not REJECTED: the objection right has been
        // exercised and there is no third examination.
        assertThat(applicationRepository.findById(c.application().getId())
                .orElseThrow().getStatus()).isEqualTo(ApplicationStatus.FINAL_REJECTION);
    }
}
