package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.admin.ReviewerService.DeleteOutcome;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.validation.ValidPassword;
import com.presscard.press_accreditation.validation.ValidPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Reviewer account management REST surface (V1.3 §C).
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**). Thin controller —
 * ReviewerService owns the rules, including the two-tier delete.
 */
@RestController
@RequestMapping("/api/admin/reviewers")
public class AdminReviewerController {

    /* ── request/response contracts ── */

    public record CreateReviewerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @ValidPhone @Size(max = 30) String phone,          // optional for staff
            @NotBlank @ValidPassword @Size(max = 100) String password
    ) {}

    public record UpdateReviewerRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @ValidPhone @Size(max = 30) String phone
    ) {}

    public record SetEnabledRequest(boolean enabled) {}

    public record ReviewerResponse(
            Long id, String email, String fullName, String phone,
            String role, boolean enabled
    ) {
        static ReviewerResponse of(User u) {
            return new ReviewerResponse(u.getId(), u.getEmail(), u.getFullName(),
                    u.getPhone(), u.getRole().name(), u.isEnabled());
        }
    }

    /** Delete result tells the admin what actually happened. */
    public record DeleteResult(String outcome, String message) {}

    private final ReviewerService reviewerService;

    public AdminReviewerController(ReviewerService reviewerService) {
        this.reviewerService = reviewerService;
    }

    /* ── endpoints ── */

    @GetMapping
    public List<ReviewerResponse> list() {
        return reviewerService.listReviewers().stream()
                .map(ReviewerResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<ReviewerResponse> create(@Valid @RequestBody CreateReviewerRequest req,
                                                   Principal principal) {
        User created = reviewerService.create(
                req.fullName(), req.email(), req.phone(), req.password(), actor(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReviewerResponse.of(created));
    }

    @PutMapping("/{id}")
    public ReviewerResponse update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateReviewerRequest req,
                                   Principal principal) {
        return ReviewerResponse.of(reviewerService.update(
                id, req.fullName(), req.email(), req.phone(), actor(principal)));
    }

    @PatchMapping("/{id}/enabled")
    public ReviewerResponse setEnabled(@PathVariable Long id,
                                       @RequestBody SetEnabledRequest req,
                                       Principal principal) {
        return ReviewerResponse.of(
                reviewerService.setEnabled(id, req.enabled(), actor(principal)));
    }

    @DeleteMapping("/{id}")
    public DeleteResult delete(@PathVariable Long id, Principal principal) {
        DeleteOutcome outcome = reviewerService.delete(id, actor(principal));
        String message = outcome == DeleteOutcome.DELETED
                ? "Réviseur supprimé."
                : "Réviseur désactivé — un historique de décisions empêche la suppression définitive.";
        return new DeleteResult(outcome.name(), message);
    }

    private String actor(Principal principal) {
        return principal != null ? principal.getName() : "unknown";
    }
}
