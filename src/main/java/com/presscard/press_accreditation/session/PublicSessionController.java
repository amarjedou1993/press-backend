package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.session.SessionDtos.PublicSessionResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public, unauthenticated view of open sessions. Returns ONLY the public
 * DTO — no internal fields, statuses, or admin metadata leak. Consumed by
 * the server-rendered /sessions page.
 *
 * "Open" = RECEIVING (the only phase accepting candidates).
 */
@RestController
@RequestMapping("/api/public/sessions")
public class PublicSessionController {

    private final SessionRepository repository;

    public PublicSessionController(SessionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PublicSessionResponse> openSessions() {
        return repository.findByStatusOrderByStartDateDesc(SessionStatus.RECEIVING)
                .stream().map(PublicSessionResponse::of).toList();
    }
}
