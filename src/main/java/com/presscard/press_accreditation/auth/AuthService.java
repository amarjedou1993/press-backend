package com.presscard.press_accreditation.auth;

import com.presscard.press_accreditation.auth.AuthDtos.AuthResponse;
import com.presscard.press_accreditation.auth.AuthDtos.LoginRequest;
import com.presscard.press_accreditation.auth.AuthDtos.RegisterCandidateRequest;
import com.presscard.press_accreditation.error.DuplicateEmailException;
import com.presscard.press_accreditation.security.JwtService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Best practices applied:
 * - Login delegates to AuthenticationManager: BCrypt comparison, disabled-user
 *   checks, and lockout hooks all live in Spring Security — never compare
 *   password hashes by hand.
 * - Emails normalized (trim + lowercase) at the door: Foo@X.com and foo@x.com
 *   are the same person, and the UNIQUE constraint should see them that way.
 * - existsByEmail is checked first for a clean 409, but the DB UNIQUE
 *   constraint remains the real guarantee against race conditions.
 */
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(AuthenticationManager authenticationManager,
                       UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());

        // Throws BadCredentialsException / DisabledException on failure —
        // translated to 401 by the GlobalExceptionHandler.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        User user = userRepository.findByEmail(email).orElseThrow();
        return toResponse(user);
    }

    @Transactional
    public AuthResponse registerCandidate(RegisterCandidateRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        User candidate = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.CANDIDATE)
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .build();

        userRepository.save(candidate);
        // Registered users are logged in immediately — no pointless second step.
        return toResponse(candidate);
    }

    private AuthResponse toResponse(User user) {
        return new AuthResponse(
                jwtService.generateToken(user),
                user.getRole().name(),
                user.getFullName());
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
