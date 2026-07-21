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
 * Proves the two things that carry the session domain:
 *  1. Date derivation — start + per-phase days → correct boundary calendar
 *     and total (and the DB CHECK constraints accept it).
 *  2. The phase machine — linear advance works; advancing CLOSED is rejected.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SessionServiceTest {

    @Autowired SessionService service;

    private CreateSessionRequest sample() {
        // 10 + 8 + 7 + 5 = 30 days from a future start.
        return new CreateSessionRequest(
                LocalDate.now().plusDays(3), 10, 8, 7, 5);
    }

    @Test
    void create_derivesBoundaryDatesAndTotal() {
        SessionResponse s = service.create(sample(), 1L);

        LocalDate start = s.startDate();
        assertThat(s.totalDays()).isEqualTo(30);
        assertThat(s.receivingEnd()).isEqualTo(start.plusDays(10));
        assertThat(s.reviewEnd()).isEqualTo(start.plusDays(18));
        assertThat(s.correctionEnd()).isEqualTo(start.plusDays(25));
        assertThat(s.reclamationEnd()).isEqualTo(start.plusDays(30));
        assertThat(s.status()).isEqualTo("PLANNED");
        assertThat(s.nextPhase()).isEqualTo("RECEIVING");
    }

    @Test
    void advancePhase_walksTheLinearMachineToClosed() {
        Long id = service.create(sample(), 1L).id();

        assertThat(service.advancePhase(id, 1L).status()).isEqualTo("RECEIVING");
        assertThat(service.advancePhase(id, 1L).status()).isEqualTo("REVIEW");
        assertThat(service.advancePhase(id, 1L).status()).isEqualTo("CORRECTION");
        assertThat(service.advancePhase(id, 1L).status()).isEqualTo("RECLAMATION");

        SessionResponse closed = service.advancePhase(id, 1L);
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.nextPhase()).isNull();

        // One step too far: advancing a CLOSED session is rejected.
        assertThatThrownBy(() -> service.advancePhase(id, 1L))
                .isInstanceOf(InvalidPhaseTransitionException.class);
    }
}
