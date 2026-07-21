package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.validation.ValidPassword;
import com.presscard.press_accreditation.validation.ValidPhone;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request/response contracts for authentication.
 *
 * Validation notes:
 * - phone: MANDATORY for candidates (feedback §2.1) and format-checked
 *   against the configurable national pattern.
 * - password: policy-checked at REGISTRATION only — LoginRequest carries
 *   no @ValidPassword on purpose; existing passwords must always log in.
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
            @NotBlank @ValidPhone @Size(max = 30) String phone,
            @NotBlank @ValidPassword @Size(max = 100) String password
    ) {}

    /** role and fullName included so the frontend can route/greet without a second call. */
    public record AuthResponse(
            String token,
            String role,
            String fullName
    ) {}
}
