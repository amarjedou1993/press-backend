package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.auth.AuthService;
import com.presscard.press_accreditation.error.DuplicateEmailException;
import com.presscard.press_accreditation.review.ReviewDecisionRepository;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reviewer account management (V1.3 §C). All actions are audited.
 *
 * The delete rule is the subtle part — a reviewer who has recorded any
 * decision CANNOT be hard-deleted, because review_decisions is the legal
 * audit trail and the reviewer_id FK must remain resolvable forever.
 * So "delete" is two-tier:
 *   - no decision history  → hard delete (row removed)
 *   - any decision history → archive (enabled=false, kept for audit)
 * The caller is told which occurred.
 */
@Service
public class ReviewerService {

    private static final Logger log = LoggerFactory.getLogger("ADMIN_AUDIT");

    public enum DeleteOutcome { DELETED, ARCHIVED }

    private final UserRepository userRepository;
    private final ReviewDecisionRepository decisionRepository;
    private final PasswordEncoder passwordEncoder;

    public ReviewerService(UserRepository userRepository,
                           ReviewDecisionRepository decisionRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.decisionRepository = decisionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<User> listReviewers() {
        return userRepository.findByRoleOrderByCreatedAtDesc(UserRole.REVIEWER);
    }

    @Transactional
    public User create(String fullName, String email, String phone, String rawPassword, String actor) {
        String normalized = AuthService.normalize(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new DuplicateEmailException(normalized);
        }
        User reviewer = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(UserRole.REVIEWER)
                .fullName(fullName.trim())
                .phone(phone)
                .build();
        userRepository.save(reviewer);
        log.info("REVIEWER_CREATED reviewer={} by={}", reviewer.getEmail(), actor);
        return reviewer;
    }

    @Transactional
    public User update(Long id, String fullName, String email, String phone, String actor) {
        User reviewer = findReviewer(id);

        String normalized = AuthService.normalize(email);
        // Email change must not collide with a different account.
        if (!normalized.equals(reviewer.getEmail())
                && userRepository.existsByEmail(normalized)) {
            throw new DuplicateEmailException(normalized);
        }
        reviewer.setFullName(fullName.trim());
        reviewer.setEmail(normalized);
        reviewer.setPhone(phone);
        userRepository.save(reviewer);
        log.info("REVIEWER_UPDATED reviewer={} by={}", reviewer.getEmail(), actor);
        return reviewer;
    }

    @Transactional
    public User setEnabled(Long id, boolean enabled, String actor) {
        User reviewer = findReviewer(id);
        reviewer.setEnabled(enabled);
        userRepository.save(reviewer);
        log.info("REVIEWER_{} reviewer={} by={}",
                enabled ? "ENABLED" : "DISABLED", reviewer.getEmail(), actor);
        return reviewer;
    }

    /**
     * Two-tier delete. Returns which outcome happened so the API can tell
     * the admin whether the account was removed or archived.
     */
    @Transactional
    public DeleteOutcome delete(Long id, String actor) {
        User reviewer = findReviewer(id);

        if (decisionRepository.existsByReviewerId(id)) {
            // Has audit history → archive, never destroy.
            reviewer.setEnabled(false);
            userRepository.save(reviewer);
            log.info("REVIEWER_ARCHIVED reviewer={} by={} (has decision history)",
                    reviewer.getEmail(), actor);
            return DeleteOutcome.ARCHIVED;
        }

        userRepository.delete(reviewer);
        log.info("REVIEWER_DELETED reviewer={} by={}", reviewer.getEmail(), actor);
        return DeleteOutcome.DELETED;
    }

    private User findReviewer(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ReviewerNotFoundException(id));
        if (user.getRole() != UserRole.REVIEWER) {
            // Guard: this endpoint manages reviewers only, never admins/candidates.
            throw new ReviewerNotFoundException(id);
        }
        return user;
    }
}
