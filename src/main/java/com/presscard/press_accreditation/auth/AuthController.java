package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.auth.AuthDtos.AuthResponse;
import com.presscard.press_accreditation.auth.AuthDtos.LoginRequest;
import com.presscard.press_accreditation.auth.AuthDtos.RegisterCandidateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controllers stay thin: HTTP in, HTTP out. All logic lives in AuthService,
 * which keeps it unit-testable without a servlet container.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterCandidateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.registerCandidate(request));
    }
}
