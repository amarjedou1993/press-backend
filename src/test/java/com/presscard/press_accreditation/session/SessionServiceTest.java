package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.TestcontainersConfiguration;
import com.presscard.press_accreditation.error.InvalidPhaseTransitionException;
import com.presscard.press_accreditation.session.SessionDtos.CreateSessionRequest;
import com.presscard.press_accreditation.session.SessionDtos.SessionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session domain rests on three guarantees, one test each:
 *  1. creation derives the initial forecast calendar;
 *  2. OPTION A — each phase gets its FULL allotted duration from the day it
 *     opens, the closing phase is stamped with its actual end date, and the
 *     rest of the calendar shifts;
 *  3. the machine is linear and CLOSED is terminal.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionServiceTest {

    @Autowired SessionService service;

    /** 10 + 8 + 7 + 5 = 30 days, starting in 3 days. */
    private CreateSessionRequest sample() {
        return new CreateSessionRequest(LocalDate.now().plusDays(3), 10, 8, 7, 5);
    }

    @Test
    void create_derivesTheInitialForecast() {
        SessionResponse s = service.create(sample(), 1L);
        LocalDate start = s.startDate();

        assertThat(s.totalDays()).isEqualTo(30);
        assertThat(s.receivingEnd()).isEqualTo(start.plusDays(10));
        assertThat(s.reviewEnd()).isEqualTo(start.plusDays(18));
        assertThat(s.correctionEnd()).isEqualTo(start.plusDays(25));
        assertThat(s.reclamationEnd()).isEqualTo(start.plusDays(30));
        assertThat(s.status()).isEqualTo("PLANNED");
        assertThat(s.nextPhase()).isEqualTo("RECEIVING");
        // Nothing is running yet, so there is nothing to count down.
        assertThat(s.daysRemainingInPhase()).isNull();
    }

    @Test
    void advancing_givesEachPhaseItsFullDuration_andStampsTheClosingPhase() {
        Long id = service.create(sample(), 1L).id();
        LocalDate today = LocalDate.now();

        // ── RECEIVING opens today, 3 days BEFORE the planned start ──
        SessionResponse receiving = service.advancePhase(id, 1L);
        assertThat(receiving.status()).isEqualTo("RECEIVING");
        // The session really begins when it starts accepting candidatures.
        assertThat(receiving.startDate()).isEqualTo(today);
        assertThat(receiving.phaseStartedAt()).isEqualTo(today);
        // Full 10 days from today, and everything downstream follows.
        assertThat(receiving.receivingEnd()).isEqualTo(today.plusDays(10));
        assertThat(receiving.reviewEnd()).isEqualTo(today.plusDays(18));
        assertThat(receiving.correctionEnd()).isEqualTo(today.plusDays(25));
        assertThat(receiving.reclamationEnd()).isEqualTo(today.plusDays(30));
        assertThat(receiving.allottedDaysInPhase()).isEqualTo(10);
        assertThat(receiving.daysRemainingInPhase()).isEqualTo(10);

        // ── REVIEW opens immediately: receiving closed 10 days early ──
        SessionResponse review = service.advancePhase(id, 1L);
        assertThat(review.status()).isEqualTo("REVIEW");
        // The closing phase is stamped with what ACTUALLY happened…
        assertThat(review.receivingEnd()).isEqualTo(today);
        // …and the commission still gets its full 8 days.
        assertThat(review.reviewEnd()).isEqualTo(today.plusDays(8));
        assertThat(review.allottedDaysInPhase()).isEqualTo(8);
        assertThat(review.daysRemainingInPhase()).isEqualTo(8);
        // The session now ends 10 days earlier than originally planned.
        assertThat(review.reclamationEnd()).isEqualTo(today.plusDays(20));

        // ── CORRECTION: full 7 days ──
        SessionResponse correction = service.advancePhase(id, 1L);
        assertThat(correction.reviewEnd()).isEqualTo(today);          // stamped
        assertThat(correction.correctionEnd()).isEqualTo(today.plusDays(7));
        assertThat(correction.daysRemainingInPhase()).isEqualTo(7);
        assertThat(correction.reclamationEnd()).isEqualTo(today.plusDays(12));

        // ── RECLAMATION: full 5 days ──
        SessionResponse reclamation = service.advancePhase(id, 1L);
        assertThat(reclamation.correctionEnd()).isEqualTo(today);     // stamped
        assertThat(reclamation.reclamationEnd()).isEqualTo(today.plusDays(5));
        assertThat(reclamation.daysRemainingInPhase()).isEqualTo(5);
    }

    @Test
    void closing_endsTheSessionToday_andStopsTheMachine() {
        Long id = service.create(sample(), 1L).id();
        for (int i = 0; i < 4; i++) service.advancePhase(id, 1L);   // → RECLAMATION

        SessionResponse closed = service.advancePhase(id, 1L);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.nextPhase()).isNull();
        assertThat(closed.reclamationEnd()).isEqualTo(LocalDate.now());
        assertThat(closed.daysRemainingInPhase()).isNull();

        assertThatThrownBy(() -> service.advancePhase(id, 1L))
                .isInstanceOf(InvalidPhaseTransitionException.class);
    }
}
