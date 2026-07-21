package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.auth.AuthService;
import com.presscard.press_accreditation.error.DuplicateEmailException;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import com.presscard.press_accreditation.validation.ValidPassword;
import com.presscard.press_accreditation.validation.ValidPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger("ADMIN_AUDIT");

    public record CreateReviewerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @ValidPhone @Size(max = 30) String phone,
            @NotBlank @ValidPassword @Size(max = 100) String password
    ) {}

    public record UserSummary(Long id, String email, String fullName, String role) {}

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/reviewers")
    @Transactional
    public ResponseEntity<UserSummary> createReviewer(@Valid @RequestBody CreateReviewerRequest request,
                                                      Principal principal) {
        String email = AuthService.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        User reviewer = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.REVIEWER)
                .fullName(request.fullName().trim())
                .phone(request.phone())
                .build();
        userRepository.save(reviewer);

        log.info("REVIEWER_CREATED reviewer={} by={}", reviewer.getEmail(),
                principal != null ? principal.getName() : "unknown");

        return ResponseEntity.status(HttpStatus.CREATED).body(new UserSummary(
                reviewer.getId(), reviewer.getEmail(), reviewer.getFullName(),
                reviewer.getRole().name()));
    }
}
