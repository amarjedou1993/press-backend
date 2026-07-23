package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.admin.ReviewerService;
import com.presscard.press_accreditation.admin.ReviewerService.DeleteOutcome;
import com.presscard.press_accreditation.error.DuplicateEmailException;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reviewer CRUD, with the two-tier delete as the centerpiece:
 *  - a reviewer with NO decision history is hard-deleted (row gone);
 *  - a reviewer WITH history is archived (kept, disabled) so the audit
 *    trail's reviewer_id foreign key stays resolvable forever.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewerServiceTest {

    @Autowired
    ReviewerService service;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;

    @Test
    void create_then_update_changesFields() {
        User r = service.create("Rev One", "rev1@test.mr", "22111111", "reviewer-pass-1", "admin");
        Long id = r.getId();

        service.update(id, "Rev One Renamed", "rev1b@test.mr", "22222222", "admin");

        User reloaded = userRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFullName()).isEqualTo("Rev One Renamed");
        assertThat(reloaded.getEmail()).isEqualTo("rev1b@test.mr");
        assertThat(reloaded.getPhone()).isEqualTo("22222222");
    }

    @Test
    void update_toExistingEmail_isRejected() {
        service.create("A", "a@test.mr", "22111111", "reviewer-pass-1", "admin");
        User b = service.create("B", "b@test.mr", "22222222", "reviewer-pass-1", "admin");

        assertThatThrownBy(() ->
                service.update(b.getId(), "B", "a@test.mr", "22222222", "admin"))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void setEnabled_togglesTheFlag() {
        User r = service.create("Toggle", "toggle@test.mr", "22111111", "reviewer-pass-1", "admin");

        service.setEnabled(r.getId(), false, "admin");
        assertThat(userRepository.findById(r.getId()).orElseThrow().isEnabled()).isFalse();

        service.setEnabled(r.getId(), true, "admin");
        assertThat(userRepository.findById(r.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void delete_withoutHistory_hardDeletes() {
        User r = service.create("Clean", "clean@test.mr", "22111111", "reviewer-pass-1", "admin");
        Long id = r.getId();

        DeleteOutcome outcome = service.delete(id, "admin");

        assertThat(outcome).isEqualTo(DeleteOutcome.DELETED);
        assertThat(userRepository.findById(id)).isEmpty();
    }

    @Test
    @Transactional
    void delete_withHistory_archivesInsteadOfDeleting() {
        User reviewer = service.create("Busy", "busy@test.mr", "22111111", "reviewer-pass-1", "admin");
        Long reviewerId = reviewer.getId();

        // Build the real supporting rows the FKs require, then a decision
        // referencing this reviewer — so existsByReviewerId is genuinely true.
        Long candidateId = insertCandidate();
        Long sessionId = insertSession(reviewerId);
        Long categoryId = firstCategoryId();
        Long applicationId = insertApplication(candidateId, sessionId, categoryId);
        insertDecision(applicationId, reviewerId);
        em.flush();

        DeleteOutcome outcome = service.delete(reviewerId, "admin");

        assertThat(outcome).isEqualTo(DeleteOutcome.ARCHIVED);
        User archived = userRepository.findById(reviewerId).orElseThrow();
        assertThat(archived.isEnabled()).isFalse();          // kept, but disabled
        assertThat(archived.getRole()).isEqualTo(UserRole.REVIEWER);
    }

    /* ── minimal row builders satisfying the real foreign keys ── */

    private Long insertCandidate() {
        User c = User.builder()
                .email("cand-" + System.nanoTime() + "@test.mr")
                .passwordHash("x").role(UserRole.CANDIDATE)
                .fullName("Cand").phone("22333333").build();
        userRepository.save(c);
        return c.getId();
    }

//    private Long insertSession(Long adminId) {
//        LocalDate start = LocalDate.now().plusDays(1);
//        return ((Number) em.createNativeQuery("""
//            INSERT INTO sessions (type, start_date, total_days, receiving_end,
//                                  review_end, correction_end, reclamation_end,
//                                  status, created_by)
//            VALUES ('CANDIDACY', :s, 30, :r, :rev, :cor, :rec, 'REVIEW', :admin)
//            RETURNING id
//            """)
//            .setParameter("s", start)
//            .setParameter("r", start.plusDays(10))
//            .setParameter("rev", start.plusDays(18))
//            .setParameter("cor", start.plusDays(25))
//            .setParameter("rec", start.plusDays(30))
//            .setParameter("admin", adminId)
//            .getSingleResult()).longValue();
//    }
private Long insertSession(Long adminId) {
    LocalDate start = LocalDate.now().plusDays(1);
    return ((Number) em.createNativeQuery("""
            INSERT INTO sessions (type, start_date, total_days,
                                  receiving_days, review_days,
                                  correction_days, reclamation_days,
                                  receiving_end, review_end,
                                  correction_end, reclamation_end,
                                  phase_started_at, status, created_by)
            VALUES ('CANDIDACY', :s, 30, 10, 8, 7, 5,
                    :r, :rev, :cor, :rec, :s, 'REVIEW', :admin)
            RETURNING id
            """)
            .setParameter("s", start)
            .setParameter("r", start.plusDays(10))
            .setParameter("rev", start.plusDays(18))
            .setParameter("cor", start.plusDays(25))
            .setParameter("rec", start.plusDays(30))
            .setParameter("admin", adminId)
            .getSingleResult()).longValue();
}

    private Long firstCategoryId() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM press_categories ORDER BY id LIMIT 1")
                .getSingleResult()).longValue();
    }

    private Long insertApplication(Long candidateId, Long sessionId, Long categoryId) {
        return ((Number) em.createNativeQuery("""
            INSERT INTO applications (candidate_id, session_id, category_id, status)
            VALUES (:c, :s, :cat, 'UNDER_REVIEW')
            RETURNING id
            """)
            .setParameter("c", candidateId)
            .setParameter("s", sessionId)
            .setParameter("cat", categoryId)
            .getSingleResult()).longValue();
    }

    private void insertDecision(Long applicationId, Long reviewerId) {
        em.createNativeQuery("""
            INSERT INTO review_decisions (application_id, reviewer_id, decision, round)
            VALUES (:app, :rev, 'APPROVE', 'INITIAL')
            """)
            .setParameter("app", applicationId)
            .setParameter("rev", reviewerId)
            .executeUpdate();
    }
}
