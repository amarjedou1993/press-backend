package com.presscard.press_accreditation.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request/response contracts for authentication.
 * Records: immutable DTOs; validation annotations fail bad input with a 400
 * BEFORE any business code runs (enforced by @Valid in the controllers).
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    public record RegisterCandidateRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @Size(max = 30) String phone,
            @NotBlank @Size(min = 8, max = 100,
                    message = "Password must be at least 8 characters") String password
    ) {}

    /** role and fullName included so the frontend can route/greet without a second call. */
    public record AuthResponse(
            String token,
            String role,
            String fullName
    ) {}
}
