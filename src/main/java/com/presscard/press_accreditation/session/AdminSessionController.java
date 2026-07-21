package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.session.SessionDtos.CreateSessionRequest;
import com.presscard.press_accreditation.session.SessionDtos.SessionResponse;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Admin session management (V1.3 §C). SUPER_ADMIN-gated by SecurityConfig
 * (/api/admin/**). The controller stays thin — SessionService owns the logic.
 */
@RestController
@RequestMapping("/api/admin/sessions")
public class AdminSessionController {

    private final SessionService sessionService;
    private final UserRepository userRepository;

    public AdminSessionController(SessionService sessionService, UserRepository userRepository) {
        this.sessionService = sessionService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public List<SessionResponse> list() {
        return sessionService.listAll();
    }

    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable Long id) {
        return sessionService.get(id);
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody CreateSessionRequest request,
                                                  Principal principal) {
        Long adminId = adminId(principal);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessionService.create(request, adminId));
    }

    /** Advance to the next phase (manual, admin-triggered — V1.3 §F). */
    @PostMapping("/{id}/advance")
    public SessionResponse advance(@PathVariable Long id, Principal principal) {
        return sessionService.advancePhase(id, adminId(principal));
    }

    private Long adminId(Principal principal) {
        return userRepository.findByEmail(principal.getName())
                .orElseThrow().getId();
    }
}
