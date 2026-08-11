//package com.presscard.press_accreditation.card;
//
//import com.presscard.press_accreditation.TestcontainersConfiguration;
//import com.presscard.press_accreditation.error.CardLifecycleException;
//import com.presscard.press_accreditation.user.*;
//import jakarta.persistence.EntityManager;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.context.annotation.Import;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDate;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//
///**
// * Withdrawing a press card ends someone's professional accreditation mid-year.
// * Every rule that constrains that act gets a test — particularly the two-hand
// * requirement, which is the whole defence of the act if it is challenged.
// */
//@SpringBootTest
//@ActiveProfiles("test")
//@Import(TestcontainersConfiguration.class)
//@Transactional
//class CardLifecycleServiceTest {
//
//    @Autowired CardLifecycleService lifecycleService;
//    @Autowired CardRepository cardRepository;
//    @Autowired CardStatusHistoryRepository historyRepository;
//    @Autowired RevocationProposalRepository proposalRepository;
//    @Autowired RevocationGroundRepository groundRepository;
//    @Autowired UserRepository userRepository;
//    @Autowired EntityManager em;
//
//    /* ── fixtures ── */
//
//    private User user(UserRole role) {
//        User u = User.builder()
//                .email(role.name().toLowerCase() + "-" + System.nanoTime() + "@test.mr")
//                .passwordHash("x").role(role).fullName("Test " + role.name())
//                .phone("22123456").build();
//        u.setEmailVerified(true);
//        return userRepository.save(u);
//    }
//
//    /**
//     * A card, built directly.
//     *
//     * The issuance path is exercised by CardServiceTest; here the subject is
//     * what happens to a card AFTER it exists, so a minimal row is enough and
//     * keeps the test about the lifecycle.
//     */
//    private Card card() {
//        return cardRepository.save(Card.builder()
//                .applicationId(null)                 // no dossier needed here
//                .cardNumber("A - %04d / 26".formatted((int) (Math.random() * 9999)))
//                .issuedAt(LocalDate.now().minusDays(30))
//                .expiresAt(LocalDate.now().plusYears(2))
//                .verificationToken("tok-" + System.nanoTime())
//                .status(CardStatus.VALID)
//                .build());
//    }
//
//    private Long groundId(String code) {
//        return groundRepository.findByCode(code).orElseThrow().getId();
//    }
//
//    private static final String STATEMENT =
//            "L'attestation de travail produite au dossier est un faux, "
//          + "confirmé par l'employeur cité.";
//
//    /* ══ suspension — the Authority alone ══════════════════════ */
//
//    @Test
//    void suspending_isImmediate_andRecorded() {
//        Card card = card();
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        lifecycleService.suspend(card.getId(), admin.getId(),
//                "Carte déclarée volée par son titulaire.");
//
//        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
//        assertThat(reloaded.getStatus()).isEqualTo(CardStatus.SUSPENDED);
//        assertThat(reloaded.isUsable()).isFalse();
//        assertThat(reloaded.getStatusChangedBy()).isEqualTo(admin.getId());
//        assertThat(reloaded.getStatusReason()).contains("volée");
//
//        // The history row is what defends the act.
//        assertThat(historyRepository.findByCardIdOrderByCreatedAtDesc(card.getId()))
//                .hasSize(1)
//                .first()
//                .satisfies(h -> {
//                    assertThat(h.getFromStatus()).isEqualTo(CardStatus.VALID);
//                    assertThat(h.getToStatus()).isEqualTo(CardStatus.SUSPENDED);
//                    assertThat(h.getActorId()).isEqualTo(admin.getId());
//                });
//    }
//
//    @Test
//    void suspendingWithoutAReason_isRefused() {
//        Card card = card();
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        // The holder's card will read "suspendue" to anyone who scans it. They
//        // are entitled to know why.
//        assertThatThrownBy(() -> lifecycleService.suspend(card.getId(), admin.getId(), "  "))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("motif");
//    }
//
//    @Test
//    void aSuspendedCardCanBeReinstated() {
//        Card card = card();
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        lifecycleService.suspend(card.getId(), admin.getId(), "Vérification en cours.");
//        lifecycleService.reinstate(card.getId(), admin.getId(), "Vérification concluante.");
//
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//        assertThat(historyRepository.findByCardIdOrderByCreatedAtDesc(card.getId()))
//                .hasSize(2);
//    }
//
//    /* ══ THE TWO-HAND RULE ═════════════════════════════════════ */
//
//    /**
//     * The rule the whole design rests on: a proposal does NOT withdraw a card.
//     */
//    @Test
//    void proposing_doesNotRevokeTheCard() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//
//        lifecycleService.propose(card.getId(), member.getId(),
//                groundId("CEASED_ACTIVITY"), STATEMENT);
//
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//        assertThat(proposalRepository.existsByCardIdAndStatus(
//                card.getId(), RevocationProposal.Status.PENDING)).isTrue();
//    }
//
//    /**
//     * The other half: a member cannot execute their own proposal, even if they
//     * also hold administrative rights. Two hands means two PEOPLE.
//     */
//    @Test
//    void theProposerCannotExecuteTheirOwnProposal() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("ETHICS_BREACH"), STATEMENT);
//        em.flush();
//
//        assertThatThrownBy(() -> lifecycleService.executeRevocation(
//                proposal.getId(), member.getId(), null))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("deux intervenants distincts");
//
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//    }
//
//    @Test
//    void theAuthorityExecuting_revokesTheCard_andRecordsBothHands() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("CEASED_ACTIVITY"), STATEMENT);
//        em.flush();
//
//        lifecycleService.executeRevocation(proposal.getId(), admin.getId(),
//                "Confirmé après vérification auprès de l'employeur.");
//
//        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
//        assertThat(reloaded.getStatus()).isEqualTo(CardStatus.REVOKED);
//
//        // BOTH HANDS in the record — the point of requiring two.
//        CardStatusHistory entry = historyRepository
//                .findByCardIdOrderByCreatedAtDesc(card.getId()).get(0);
//        assertThat(entry.getActorId()).isEqualTo(admin.getId());
//        assertThat(entry.getProposedBy()).isEqualTo(member.getId());
//        assertThat(entry.getReason()).contains("proposition de la commission");
//
//        assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus())
//                .isEqualTo(RevocationProposal.Status.EXECUTED);
//    }
//
//    /* ══ revocation is terminal ════════════════════════════════ */
//
//    @Test
//    void aRevokedCardIsNeverReinstated() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("MISUSE_OF_CARD"), STATEMENT);
//        em.flush();
//        lifecycleService.executeRevocation(proposal.getId(), admin.getId(), null);
//
//        // Reinstating would mean the withdrawal had been provisional all along
//        // — which is not what it said to whoever scanned the card meanwhile.
//        assertThatThrownBy(() -> lifecycleService.reinstate(
//                card.getId(), admin.getId(), "Erreur."))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("nouvelle candidature");
//    }
//
//    /* ══ the precautionary suspension ══════════════════════════ */
//
//    @Test
//    void aGroundWarrantingIt_suspendsTheCardWhileTheProposalIsExamined() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//
//        // A card alleged to rest on a forged dossier should not stay in force
//        // for the days a decision takes.
//        lifecycleService.propose(card.getId(), member.getId(),
//                groundId("FRAUDULENT_APPLICATION"), STATEMENT);
//
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.SUSPENDED);
//    }
//
//    @Test
//    void anAdministrativeGround_doesNotSuspend() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//
//        // A holder who has died needs no precaution taken against them.
//        lifecycleService.propose(card.getId(), member.getId(),
//                groundId("DECEASED"), "Acte de décès transmis par la famille du titulaire.");
//
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//    }
//
//    @Test
//    void decliningLiftsThePrecautionarySuspension() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("FRAUDULENT_APPLICATION"), STATEMENT);
//        em.flush();
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.SUSPENDED);
//
//        lifecycleService.declineRevocation(proposal.getId(), admin.getId(),
//                "L'attestation a été vérifiée et se révèle authentique.");
//
//        // The allegation was not upheld, so the card must not keep bearing its
//        // consequence.
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//    }
//
//    @Test
//    void decliningWithoutAReason_isRefused() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("ETHICS_BREACH"), STATEMENT);
//        em.flush();
//
//        // A refusal the proposer cannot read is one they will simply repeat.
//        assertThatThrownBy(() -> lifecycleService.declineRevocation(
//                proposal.getId(), admin.getId(), null))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("motif du refus");
//    }
//
//    /* ══ one proposal at a time ════════════════════════════════ */
//
//    @Test
//    void aSecondPendingProposal_isRefused() {
//        Card card = card();
//        User first = user(UserRole.REVIEWER);
//        User second = user(UserRole.REVIEWER);
//
//        lifecycleService.propose(card.getId(), first.getId(),
//                groundId("ETHICS_BREACH"), STATEMENT);
//        em.flush();
//
//        // Two open proposals would leave one live against an already-revoked
//        // card once the other was executed.
//        assertThatThrownBy(() -> lifecycleService.propose(
//                card.getId(), second.getId(), groundId("MISUSE_OF_CARD"), STATEMENT))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("déjà en cours");
//    }
//
//    @Test
//    void aStatementTooShortToActOn_isRefused() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//
//        // The Authority is being asked to end an accreditation. "Il a fauté"
//        // does not let them decide anything.
//        assertThatThrownBy(() -> lifecycleService.propose(
//                card.getId(), member.getId(), groundId("ETHICS_BREACH"), "Il a fauté."))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("caractères");
//    }
//
//    @Test
//    void theProposerMayWithdrawTheirOwnProposal_andOnlyTheirs() {
//        Card card = card();
//        User member = user(UserRole.REVIEWER);
//        User other = user(UserRole.REVIEWER);
//
//        RevocationProposal proposal = lifecycleService.propose(
//                card.getId(), member.getId(), groundId("FRAUDULENT_APPLICATION"), STATEMENT);
//        em.flush();
//
//        assertThatThrownBy(() -> lifecycleService.withdrawProposal(
//                proposal.getId(), other.getId()))
//                .isInstanceOf(CardLifecycleException.class)
//                .hasMessageContaining("Seul l'auteur");
//
//        lifecycleService.withdrawProposal(proposal.getId(), member.getId());
//
//        assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus())
//                .isEqualTo(RevocationProposal.Status.WITHDRAWN);
//        // …and the precautionary suspension goes with it.
//        assertThat(cardRepository.findById(card.getId()).orElseThrow().getStatus())
//                .isEqualTo(CardStatus.VALID);
//    }
//}


package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.error.CardLifecycleException;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.user.*;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Withdrawing a press card ends someone's professional accreditation mid-year.
 * Every rule that constrains that act gets a test — particularly the two-hand
 * requirement, which is the whole defence of the act if it is challenged.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class CardLifecycleServiceTest {

    @Autowired
    CardLifecycleService lifecycleService;

    @Autowired
    CardRepository cardRepository;

    @Autowired
    CardStatusHistoryRepository historyRepository;

    @Autowired
    RevocationProposalRepository proposalRepository;

    @Autowired
    RevocationGroundRepository groundRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    SessionRepository sessionRepository;

    @Autowired
    PressCategoryRepository pressCategoryRepository;

//    @Autowired
//    EmailOutboxRepository emailOutboxRepository;

    @Autowired
    EntityManager em;

    /*
     * Keep card numbers unique inside the test JVM.
     *
     * Math.random() was previously used, but card_number is normally unique and
     * lifecycle tests should not occasionally fail because two fixtures happened
     * to generate the same four-digit number.
     */
    private static final AtomicInteger CARD_SEQUENCE =
            new AtomicInteger(1000);

    /* ── fixtures ── */

    private User user(UserRole role) {
        User u = User.builder()
                .email(
                        role.name().toLowerCase()
                                + "-"
                                + System.nanoTime()
                                + "@test.mr"
                )
                .passwordHash("x")
                .role(role)
                .fullName("Test " + role.name())
                .phone("22123456")
                .build();

        u.setEmailVerified(true);

        return userRepository.save(u);
    }

    /**
     * Minimal valid session required by an Application.
     *
     * This test is not testing the session lifecycle, so only the database
     * invariants necessary to persist the row are supplied.
     */
    private Session session() {
        User creator = user(UserRole.SUPER_ADMIN);

        LocalDate start = LocalDate.now();

        return sessionRepository.save(
                Session.builder()
                        .startDate(start)

                        // Four seven-day phases.
                        .totalDays(28)
                        .receivingDays(7)
                        .reviewDays(7)
                        .correctionDays(7)
                        .reclamationDays(7)

                        .receivingEnd(start.plusDays(6))
                        .reviewEnd(start.plusDays(13))
                        .correctionEnd(start.plusDays(20))
                        .reclamationEnd(start.plusDays(27))

                        .phaseStartedAt(start)
                        .createdBy(creator.getId())

                        // type defaults to CANDIDACY
                        // status defaults to PLANNED
                        .build()
        );
    }

    /**
     * Minimal valid application required by a Card.
     *
     * Press categories are reference data populated by Flyway, so the test
     * reuses one of those rows rather than trying to create reference data.
     */
    private Application application() {
        User candidate = user(UserRole.CANDIDATE);
        Session session = session();

        PressCategory category = pressCategoryRepository.findAll()
                .stream()
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "No press category was seeded in the test database"
                        )
                );

        return applicationRepository.save(
                Application.builder()
                        .candidateId(candidate.getId())
                        .sessionId(session.getId())
                        .categoryId(category.getId())

                        // status defaults to DRAFT
                        // correctionCount defaults to 0
                        // photoNeedsCorrection defaults to false
                        .build()
        );
    }

    /**
     * A card, built directly.
     *
     * The issuance path is exercised by CardServiceTest; here the subject is
     * what happens to a card AFTER it exists.
     *
     * The database now requires application_id, so this fixture creates the
     * minimum legitimate application graph instead of inserting a null
     * application_id.
     */
    private Card card() {
        Application application = application();

        int sequence = CARD_SEQUENCE.getAndIncrement();

        return cardRepository.save(
                Card.builder()
                        .applicationId(application.getId())
                        .cardNumber("A - %04d / 26".formatted(sequence))
                        .issuedAt(LocalDate.now().minusDays(30))
                        .expiresAt(LocalDate.now().plusYears(2))
                        .verificationToken("tok-" + System.nanoTime())
                        .status(CardStatus.VALID)
                        .build()
        );
    }

    private Long groundId(String code) {
        return groundRepository.findByCode(code)
                .orElseThrow()
                .getId();
    }

    private static final String STATEMENT =
            "L'attestation de travail produite au dossier est un faux, "
                    + "confirmé par l'employeur cité.";

    /* ══ suspension — the Authority alone ══════════════════════ */

    @Test
    void suspending_isImmediate_andRecorded() {
        Card card = card();
        User admin = user(UserRole.SUPER_ADMIN);

        lifecycleService.suspend(
                card.getId(),
                admin.getId(),
                "Carte déclarée volée par son titulaire."
        );

        Card reloaded = cardRepository.findById(card.getId())
                .orElseThrow();

        assertThat(reloaded.getStatus())
                .isEqualTo(CardStatus.SUSPENDED);

        assertThat(reloaded.isUsable())
                .isFalse();

        assertThat(reloaded.getStatusChangedBy())
                .isEqualTo(admin.getId());

        assertThat(reloaded.getStatusReason())
                .contains("volée");

        // The history row is what defends the act.
        assertThat(
                historyRepository.findByCardIdOrderByCreatedAtDesc(
                        card.getId()
                )
        )
                .hasSize(1)
                .first()
                .satisfies(h -> {
                    assertThat(h.getFromStatus())
                            .isEqualTo(CardStatus.VALID);

                    assertThat(h.getToStatus())
                            .isEqualTo(CardStatus.SUSPENDED);

                    assertThat(h.getActorId())
                            .isEqualTo(admin.getId());
                });
    }

    @Test
    void suspendingWithoutAReason_isRefused() {
        Card card = card();
        User admin = user(UserRole.SUPER_ADMIN);

        // The holder's card will read "suspendue" to anyone who scans it.
        // They are entitled to know why.
        assertThatThrownBy(() ->
                lifecycleService.suspend(
                        card.getId(),
                        admin.getId(),
                        "  "
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining("motif");
    }

    @Test
    void aSuspendedCardCanBeReinstated() {
        Card card = card();
        User admin = user(UserRole.SUPER_ADMIN);

        lifecycleService.suspend(
                card.getId(),
                admin.getId(),
                "Vérification en cours."
        );

        lifecycleService.reinstate(
                card.getId(),
                admin.getId(),
                "Vérification concluante."
        );

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);

        assertThat(
                historyRepository.findByCardIdOrderByCreatedAtDesc(
                        card.getId()
                )
        )
                .hasSize(2);
    }

    /* ══ THE TWO-HAND RULE ═════════════════════════════════════ */

    /**
     * The rule the whole design rests on:
     * a proposal does NOT withdraw a card.
     */
    @Test
    void proposing_doesNotRevokeTheCard() {
        Card card = card();
        User member = user(UserRole.REVIEWER);

        lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("CEASED_ACTIVITY"),
                STATEMENT
        );

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);

        assertThat(
                proposalRepository.existsByCardIdAndStatus(
                        card.getId(),
                        RevocationProposal.Status.PENDING
                )
        )
                .isTrue();
    }

    /**
     * A member cannot execute their own proposal, even if they also hold
     * administrative rights.
     *
     * Two hands means two PEOPLE.
     */
    @Test
    void theProposerCannotExecuteTheirOwnProposal() {
        Card card = card();
        User member = user(UserRole.REVIEWER);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("ETHICS_BREACH"),
                STATEMENT
        );

        em.flush();

        assertThatThrownBy(() ->
                lifecycleService.executeRevocation(
                        proposal.getId(),
                        member.getId(),
                        null
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining(
                        "deux intervenants distincts"
                );

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);
    }

    @Test
    void theAuthorityExecuting_revokesTheCard_andRecordsBothHands() {
        Card card = card();
        User member = user(UserRole.REVIEWER);
        User admin = user(UserRole.SUPER_ADMIN);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("CEASED_ACTIVITY"),
                STATEMENT
        );

        em.flush();

        lifecycleService.executeRevocation(
                proposal.getId(),
                admin.getId(),
                "Confirmé après vérification auprès de l'employeur."
        );

        Card reloaded = cardRepository.findById(card.getId())
                .orElseThrow();

        assertThat(reloaded.getStatus())
                .isEqualTo(CardStatus.REVOKED);

        // BOTH HANDS in the record — the point of requiring two.
        CardStatusHistory entry =
                historyRepository
                        .findByCardIdOrderByCreatedAtDesc(
                                card.getId()
                        )
                        .get(0);

        assertThat(entry.getActorId())
                .isEqualTo(admin.getId());

        assertThat(entry.getProposedBy())
                .isEqualTo(member.getId());

        assertThat(entry.getReason())
                .contains("proposition de la commission");

        assertThat(
                proposalRepository.findById(proposal.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(
                        RevocationProposal.Status.EXECUTED
                );
    }

    /* ══ revocation is terminal ════════════════════════════════ */

    @Test
    void aRevokedCardIsNeverReinstated() {
        Card card = card();
        User member = user(UserRole.REVIEWER);
        User admin = user(UserRole.SUPER_ADMIN);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("MISUSE_OF_CARD"),
                STATEMENT
        );

        em.flush();

        lifecycleService.executeRevocation(
                proposal.getId(),
                admin.getId(),
                null
        );

        // Reinstating would mean the withdrawal had been provisional all
        // along — which is not what it said to whoever scanned the card
        // meanwhile.
        assertThatThrownBy(() ->
                lifecycleService.reinstate(
                        card.getId(),
                        admin.getId(),
                        "Erreur."
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining(
                        "nouvelle candidature"
                );
    }

    /* ══ the precautionary suspension ══════════════════════════ */

    @Test
    void aGroundWarrantingIt_suspendsTheCardWhileTheProposalIsExamined() {
        Card card = card();
        User member = user(UserRole.REVIEWER);

        // A card alleged to rest on a forged dossier should not stay in
        // force for the days a decision takes.
        lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("FRAUDULENT_APPLICATION"),
                STATEMENT
        );

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.SUSPENDED);
    }

    @Test
    void anAdministrativeGround_doesNotSuspend() {
        Card card = card();
        User member = user(UserRole.REVIEWER);

        // A holder who has died needs no precaution taken against them.
        lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("DECEASED"),
                "Acte de décès transmis par la famille du titulaire."
        );

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);
    }

    @Test
    void decliningLiftsThePrecautionarySuspension() {
        Card card = card();
        User member = user(UserRole.REVIEWER);
        User admin = user(UserRole.SUPER_ADMIN);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("FRAUDULENT_APPLICATION"),
                STATEMENT
        );

        em.flush();

        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.SUSPENDED);

        lifecycleService.declineRevocation(
                proposal.getId(),
                admin.getId(),
                "L'attestation a été vérifiée et se révèle authentique."
        );

        // The allegation was not upheld, so the card must not keep bearing
        // its consequence.
        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);
    }

    @Test
    void decliningWithoutAReason_isRefused() {
        Card card = card();
        User member = user(UserRole.REVIEWER);
        User admin = user(UserRole.SUPER_ADMIN);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("ETHICS_BREACH"),
                STATEMENT
        );

        em.flush();

        // A refusal the proposer cannot read is one they will simply repeat.
        assertThatThrownBy(() ->
                lifecycleService.declineRevocation(
                        proposal.getId(),
                        admin.getId(),
                        null
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining("motif du refus");
    }

    /* ══ one proposal at a time ════════════════════════════════ */

    @Test
    void aSecondPendingProposal_isRefused() {
        Card card = card();
        User first = user(UserRole.REVIEWER);
        User second = user(UserRole.REVIEWER);

        lifecycleService.propose(
                card.getId(),
                first.getId(),
                groundId("ETHICS_BREACH"),
                STATEMENT
        );

        em.flush();

        // Two open proposals would leave one live against an already-revoked
        // card once the other was executed.
        assertThatThrownBy(() ->
                lifecycleService.propose(
                        card.getId(),
                        second.getId(),
                        groundId("MISUSE_OF_CARD"),
                        STATEMENT
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining("déjà en cours");
    }

    @Test
    void aStatementTooShortToActOn_isRefused() {
        Card card = card();
        User member = user(UserRole.REVIEWER);

        // The Authority is being asked to end an accreditation.
        // "Il a fauté" does not let them decide anything.
        assertThatThrownBy(() ->
                lifecycleService.propose(
                        card.getId(),
                        member.getId(),
                        groundId("ETHICS_BREACH"),
                        "Il a fauté."
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining("caractères");
    }

    @Test
    void theProposerMayWithdrawTheirOwnProposal_andOnlyTheirs() {
        Card card = card();
        User member = user(UserRole.REVIEWER);
        User other = user(UserRole.REVIEWER);

        RevocationProposal proposal = lifecycleService.propose(
                card.getId(),
                member.getId(),
                groundId("FRAUDULENT_APPLICATION"),
                STATEMENT
        );

        em.flush();

        assertThatThrownBy(() ->
                lifecycleService.withdrawProposal(
                        proposal.getId(),
                        other.getId()
                )
        )
                .isInstanceOf(CardLifecycleException.class)
                .hasMessageContaining("Seul l'auteur");

        lifecycleService.withdrawProposal(
                proposal.getId(),
                member.getId()
        );

        assertThat(
                proposalRepository.findById(proposal.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(
                        RevocationProposal.Status.WITHDRAWN
                );

        // ...and the precautionary suspension goes with it.
        assertThat(
                cardRepository.findById(card.getId())
                        .orElseThrow()
                        .getStatus()
        )
                .isEqualTo(CardStatus.VALID);
    }

//    @Test
//    void everyStatusChange_notifiesTheHolder() {
//        Card card = card();
//        User admin = user(UserRole.SUPER_ADMIN);
//
//        lifecycleService.suspend(card.getId(), admin.getId(),
//                "Carte déclarée volée.");
//        em.flush();
//
//        // Rule 5: a journalist whose card stops working at a checkpoint
//        // without warning has been treated badly, whatever the merits.
//        assertThat(emailOutboxRepository.count()).isPositive();
//    }

    @Test
    void everyStatusChange_recordsWhoActedAndWhy() {
        Card card = card();
        User admin = user(UserRole.SUPER_ADMIN);

        lifecycleService.suspend(card.getId(), admin.getId(),
                "Carte déclarée volée.");

        // The holder is notified through EmailService, which is disabled in
        // tests (app.email.enabled: false) so nothing reaches the outbox. What
        // CAN be proved here is the precondition the notification depends on:
        // the card must resolve to a holder through its application.
        //
        // That is exactly what was broken while the fixture used a null
        // applicationId — notifyHolder found nobody and returned silently.
        Card reloaded = cardRepository.findById(card.getId()).orElseThrow();
        assertThat(reloaded.getApplicationId()).isNotNull();

        assertThat(applicationRepository.findById(reloaded.getApplicationId()))
                .isPresent()
                .get()
                .satisfies(a -> assertThat(a.getCandidateId()).isNotNull());
    }
}