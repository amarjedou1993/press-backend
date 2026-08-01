package com.presscard.press_accreditation.objection;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.*;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.review.*;
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
 * The objection is the candidate's only recourse against a rejection. Every
 * rule that protects it — and the one that can make it impossible — gets a
 * test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class ObjectionServiceTest {

    @Autowired ObjectionService objectionService;
    @Autowired ObjectionRepository objectionRepository;
    @Autowired ObjectionReasonRepository reasonRepository;
    @Autowired ReviewService reviewService;
    @Autowired ApplicationService applicationService;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired ReviewDecisionRepository decisionRepository;
    @Autowired CandidateProfileRepository profileRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    /* ── fixtures ── */

    private User user(UserRole role) {
        User u = User.builder()
                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(role).fullName("Test " + role.name())
                .phone("22123456").build();
        u.setEmailVerified(true);
        return userRepository.save(u);
    }

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

    /** Move a session into its reclamation phase, deadline relative to today. */
    private Session advanceToReclamation(Session session, int daysFromNow) {
        LocalDate end = LocalDate.now().plusDays(daysFromNow);
        session.setStartDate(end.minusDays(35));
        session.setReceivingEnd(end.minusDays(25));
        session.setReviewEnd(end.minusDays(17));
        session.setCorrectionEnd(end.minusDays(10));
        session.setReclamationEnd(end);
        session.setStatus(SessionStatus.RECLAMATION);
        session.setPhaseStartedAt(end.minusDays(5));
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

    private Long reasonId(String code) {
        return reasonRepository.findByCode(code).orElseThrow().getId();
    }

    private record Rejected(Application application, User candidate, User rejecter, Session session) {}

    /** A dossier rejected by one reviewer, session in its reclamation phase. */
    private Rejected rejected(int reclamationDaysFromNow) {
        User candidate = user(UserRole.CANDIDATE);
        profileRepository.save(CandidateProfile.builder()
                .userId(candidate.getId()).nni("1234567890")
                .birthdate(LocalDate.of(1990, 5, 14)).birthplace("Nouakchott")
                .photoPath("photos/1/x.jpg").photoUploadedAt(OffsetDateTime.now())
                .build());

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
            VALUES (:app, 'WORK_CERTIFICATE', 'FILE', '2026/07/1/x.pdf', 1)
            """).setParameter("app", app.getId()).executeUpdate();
        em.flush();

        applicationService.submit(app.getId(), candidate.getId());

        User rejecter = user(UserRole.REVIEWER);
        reviewService.claim(app.getId(), rejecter.getId());
        reviewService.reject(app.getId(), rejecter.getId(),
                RejectionGround.INELIGIBLE,
                "Le candidat n'exerce pas une activité journalistique régulière.");
        em.flush();

        advanceToReclamation(s, reclamationDaysFromNow);
        em.flush();
        em.clear();

        return new Rejected(
                applicationRepository.findById(app.getId()).orElseThrow(),
                candidate, rejecter, s);
    }

    /* ══ filing ════════════════════════════════════════════ */

    @Test
    void filing_movesTheDossierToReclamation_andReturnsItToThePool() {
        Rejected r = rejected(5);
        user(UserRole.REVIEWER);            // a second member exists

        objectionService.file(r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "La décision indique que je n'exerce pas régulièrement, alors que "
              + "mon attestation couvre les douze derniers mois.");

        Application reloaded = applicationRepository
                .findById(r.application().getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(ApplicationStatus.UNDER_RECLAMATION);
        assertThat(reloaded.getClaimedBy()).isNull();      // back in the pool
        assertThat(objectionRepository.existsByApplicationId(r.application().getId())).isTrue();
    }

    @Test
    void theObjectionPinsTheDecisionItContests() {
        Rejected r = rejected(5);
        user(UserRole.REVIEWER);

        objectionService.file(r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "Ma situation professionnelle a été mal appréciée par la commission.");

        Objection filed = objectionRepository
                .findByApplicationId(r.application().getId()).orElseThrow();

        // Pinned, so the record stays unambiguous once the reclamation
        // produces a decision of its own.
        assertThat(filed.getContestedDecisionId()).isNotNull();
        ReviewDecision contested = decisionRepository
                .findById(filed.getContestedDecisionId()).orElseThrow();
        assertThat(contested.getDecision()).isEqualTo(DecisionType.REJECT);
        assertThat(contested.getReviewerId()).isEqualTo(r.rejecter().getId());
    }

    /* ══ once only ═════════════════════════════════════════ */

    @Test
    void aSecondObjection_isRefused() {
        Rejected r = rejected(5);
        user(UserRole.REVIEWER);

        objectionService.file(r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "Première contestation, suffisamment détaillée pour être recevable.");
        em.flush();

        // A right that could be exercised twice could be exercised indefinitely.
        assertThatThrownBy(() -> objectionService.file(
                r.application().getId(), r.candidate().getId(),
                reasonId("NEW_EVIDENCE"),
                "Seconde tentative, tout aussi détaillée que la première."))
                .isInstanceOf(ObjectionNotAllowedException.class)
                .hasMessageContaining("déjà déposé");
    }

    /* ══ the window ════════════════════════════════════════ */

    @Test
    void objectingAfterTheDeadline_isRefused() {
        Rejected r = rejected(-1);          // the window closed yesterday
        user(UserRole.REVIEWER);

        assertThatThrownBy(() -> objectionService.file(
                r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "Contestation déposée hors délai, mais suffisamment détaillée."))
                .isInstanceOf(ObjectionNotAllowedException.class)
                .hasMessageContaining("délai");
    }

    @Test
    void objectingAgainstSomethingOtherThanARejection_isRefused() {
        User candidate = user(UserRole.CANDIDATE);
        Session s = openSession();
        Application draft = applicationService.startOrResume(
                candidate.getId(), s.getId(), categoryId());

        assertThatThrownBy(() -> objectionService.file(
                draft.getId(), candidate.getId(), reasonId("MATERIAL_ERROR"),
                "Contestation contre un brouillon, ce qui n'a pas de sens."))
                .isInstanceOf(ObjectionNotAllowedException.class);
    }

    /* ══ the argument ══════════════════════════════════════ */

    @Test
    void anArgumentTooShortToActOn_isRefused() {
        Rejected r = rejected(5);
        user(UserRole.REVIEWER);

        // A ground alone sends the second reviewer to re-read an entire
        // dossier with no idea what they are looking for.
        assertThatThrownBy(() -> objectionService.file(
                r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"), "Pas d'accord."))
                .isInstanceOf(ObjectionNotAllowedException.class)
                .hasMessageContaining("caractères");
    }

    /* ══ THE OPERATIONAL RULE ══════════════════════════════ */

    /**
     * The failure the different-reviewer rule invites: logically sound,
     * practically impossible with a one-member commission. It must be refused
     * AT FILING, not discovered when nobody can claim the file.
     */
    @Test
    void withNoOtherCommissionMember_theObjectionIsRefusedAtFiling() {
        Rejected r = rejected(5);
        // The rejecter is the ONLY reviewer — nobody may re-examine.

        assertThatThrownBy(() -> objectionService.file(
                r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "Contestation parfaitement recevable, mais personne ne peut l'examiner."))
                .isInstanceOf(NoEligibleReviewerException.class)
                .hasMessageContaining("aucun autre membre");

        // …and nothing was written: a refused filing changes nothing.
        assertThat(objectionRepository.existsByApplicationId(r.application().getId()))
                .isFalse();
        assertThat(applicationRepository.findById(r.application().getId())
                .orElseThrow().getStatus()).isEqualTo(ApplicationStatus.REJECTED);
    }

    /* ══ eligibility, as the UI reads it ═══════════════════ */

    @Test
    void eligibility_explainsWhyNotWhenItCannotBeFiled() {
        Rejected r = rejected(5);
        user(UserRole.REVIEWER);

        var before = objectionService.eligibility(
                r.application().getId(), r.candidate().getId());
        assertThat(before.canObject()).isTrue();
        assertThat(before.alreadyFiled()).isFalse();
        assertThat(before.contestedJustification()).contains("activité journalistique");

        objectionService.file(r.application().getId(), r.candidate().getId(),
                reasonId("MATERIAL_ERROR"),
                "Contestation détaillée, déposée dans les délais impartis.");
        em.flush();

        var after = objectionService.eligibility(
                r.application().getId(), r.candidate().getId());
        assertThat(after.canObject()).isFalse();
        assertThat(after.alreadyFiled()).isTrue();
        assertThat(after.blockedReasonFr()).contains("une seule");
    }

    /* ══ another candidate's dossier ═══════════════════════ */

    @Test
    void anotherCandidatesDossier_isInvisible() {
        Rejected r = rejected(5);
        User stranger = user(UserRole.CANDIDATE);

        assertThatThrownBy(() -> objectionService.eligibility(
                r.application().getId(), stranger.getId()))
                .isInstanceOf(ApplicationNotFoundException.class);
    }
}
