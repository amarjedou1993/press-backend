package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.application.ApplicationStatus;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool listing must cost the same whether it returns five dossiers or
 * fifty.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ WHY THIS TEST DOES NOT ASSERT A NUMBER.
 *
 * "Exactly five queries" would fail the day someone adds a legitimate sixth,
 * and the failure would say nothing about whether the change was good. It
 * would be a test of an implementation detail, and it would be deleted the
 * second time it cried wolf.
 *
 * What matters is the SHAPE: constant, not linear. A listing costing four
 * queries per row is what capped this endpoint near seven requests a second —
 * roughly a hundred sequential round trips for a page of twenty-four, all
 * holding one connection from a pool of ten.
 *
 * So the same code runs twice over lists of different sizes, and the counts
 * must match. That property is worth defending; the exact number is not.
 * ───────────────────────────────────────────────────────────────────────
 *
 * ⚠️ IT GUARDS A REGRESSION NOBODY CATCHES IN REVIEW. Adding
 * `userRepository.findById(...)` inside toPoolItem reads perfectly well and
 * costs one query per dossier. This is what notices.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.SQL=DEBUG"
})
class PoolQueryCountTest {

    private static final String REVIEWER_EMAIL = "pool-count-reviewer@test.mr";

    @Autowired ReviewController controller;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired UserRepository userRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired PressCategoryRepository categoryRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private Long sessionId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        /*
         * ⚠️ READ, never created.
         *
         * PressCategory carries no builder and no setters — it is reference
         * data owned by Flyway, and the entity says so by being unwritable.
         * The seed migration guarantees at least one row; if that ever stops
         * being true, this fails with a sentence rather than a null pointer
         * four lines later.
         */
        PressCategory category = categoryRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No press category seeded — the V2 migration should have "
                        + "created them, and this test depends on it."));
        categoryId = category.getId();

        Session session = sessionRepository.save(Session.builder()
                .startDate(LocalDate.now().minusDays(5))
                .totalDays(40)
                .receivingDays(10).reviewDays(10)
                .correctionDays(10).reclamationDays(10)
                .receivingEnd(LocalDate.now().plusDays(5))
                .reviewEnd(LocalDate.now().plusDays(15))
                .correctionEnd(LocalDate.now().plusDays(25))
                .reclamationEnd(LocalDate.now().plusDays(35))
                .phaseStartedAt(LocalDate.now().minusDays(5))
                .status(SessionStatus.REVIEW)
                .createdBy(1L)
                .build());
        sessionId = session.getId();

        userRepository.findByEmail(REVIEWER_EMAIL).orElseGet(() ->
                userRepository.save(user(REVIEWER_EMAIL, UserRole.REVIEWER)));
    }

    @Test
    @WithMockUser(username = REVIEWER_EMAIL, roles = "REVIEWER")
    @DisplayName("the pool listing costs the same for 5 dossiers as for 25")
    void queryCountDoesNotGrowWithRows() {
        seed(5);
        long forFive = countQueriesForAll();

        seed(20);   // 25 in total
        long forTwentyFive = countQueriesForAll();

        /*
         * ⚠️ EQUAL, not "roughly equal".
         *
         * Every lookup is batched, so five times the rows must cost exactly
         * the same number of statements. Any drift means something inside the
         * mapping is talking to the database again — which is the whole
         * failure this guards.
         */
        assertThat(forTwentyFive)
                .as("the listing must be constant-cost, not linear: "
                        + "%d queries for 5 dossiers, %d for 25", forFive, forTwentyFive)
                .isEqualTo(forFive);

        /*
         * And a sanity bound. Without it, a listing that is linear in BOTH
         * runs could still pass by being equally bad twice — which is
         * arithmetically impossible here, but would not be if someone later
         * made the two runs the same size while editing.
         */
        assertThat(forFive)
                .as("a single listing should be a handful of queries, not dozens")
                .isLessThan(15);
    }

    /* ══ helpers ══ */

    /**
     * Run the endpoint and count the statements it issued.
     *
     * ⚠️ Cleared immediately BEFORE the call, not after: the seeding above
     * issues dozens of inserts, and they would otherwise be counted as the
     * listing's cost.
     */
    private long countQueriesForAll() {
        statistics.clear();
        List<?> items = controller.all(principal());
        assertThat(items).isNotEmpty();
        return statistics.getPrepareStatementCount();
    }

    private void seed(int count) {
        for (int i = 0; i < count; i++) {
            User candidate = userRepository.save(user(
                    "pool-count-candidate-" + System.nanoTime() + "@test.mr",
                    UserRole.CANDIDATE));

            applicationRepository.save(Application.builder()
                    .candidateId(candidate.getId())
                    .sessionId(sessionId)
                    .categoryId(categoryId)
                    .status(ApplicationStatus.UNDER_REVIEW)
                    .submittedAt(OffsetDateTime.now().minusDays(2))
                    .build());
        }
    }

    private User user(String email, UserRole role) {
        return User.builder()
                .email(email)
                .passwordHash("{noop}x")
                .role(role)
                .fullName("Test " + role.name())
                .emailVerified(true)
                .build();
    }

    /** Principal is a single-method interface; the endpoint reads only getName. */
    private Principal principal() {
        return () -> REVIEWER_EMAIL;
    }
}
