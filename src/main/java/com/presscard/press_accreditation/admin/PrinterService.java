package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.auth.AuthService;
import com.presscard.press_accreditation.card.PrintRunRepository;
import com.presscard.press_accreditation.error.DuplicateEmailException;
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
 * Producer account management. All actions are audited.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE SAME TWO-TIER DELETE AS A REVIEWER'S, FOR THE SAME REASON.
 *
 * A reviewer who has decided cannot be destroyed, because review_decisions is
 * the legal trail and its reviewer_id must stay resolvable for ever.
 *
 * A producer who has PRODUCED is in exactly that position. print_runs.
 * printed_by answers "who took these cards out of the building", and an
 * external contractor is precisely the party about whom that question gets
 * asked. Deleting the row would leave the run with an id resolving to
 * nobody — and the record would fail at the moment it mattered.
 *
 * So:
 *   no production history  → hard delete
 *   any production history → archive (enabled=false, kept)
 * ───────────────────────────────────────────────────────────────────────
 *
 * ⚠️ THIS IS A PARALLEL OF ReviewerService, NOT AN ABSTRACTION OF IT.
 *
 * The two could be one generic service. Making that change would mean
 * refactoring a working file that governs who may decide accreditations — and
 * the two differ in exactly one line, the history check. A deliberate
 * duplication, noted so it is a choice rather than an oversight.
 */
@Service
public class PrinterService {

    private static final Logger log = LoggerFactory.getLogger("ADMIN_AUDIT");

    public enum DeleteOutcome { DELETED, ARCHIVED }

    private final UserRepository userRepository;
    private final PrintRunRepository runRepository;
    private final PasswordEncoder passwordEncoder;

    public PrinterService(UserRepository userRepository,
                          PrintRunRepository runRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.runRepository = runRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<User> listPrinters() {
        return userRepository.findByRoleOrderByCreatedAtDesc(UserRole.PRINTER);
    }

    @Transactional
    public User create(String fullName, String email, String phone,
                       String rawPassword, String actor) {
        String normalized = AuthService.normalize(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new DuplicateEmailException(normalized);
        }

        User printer = User.builder()
                .email(normalized)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .role(UserRole.PRINTER)
                .fullName(fullName.trim())
                .phone(phone)
                // ⚠️ Verified on creation, like a reviewer. A staff account is
                // handed over by an administrator who already knows the
                // address — there is no inbox to prove control of, and an
                // unverified producer could not work until they found a mail
                // nobody sent them.
                .emailVerified(true)
                .build();
        userRepository.save(printer);

        log.info("PRINTER_CREATED printer={} by={}", printer.getEmail(), actor);
        return printer;
    }

    @Transactional
    public User update(Long id, String fullName, String email, String phone, String actor) {
        User printer = findPrinter(id);

        String normalized = AuthService.normalize(email);
        // Email change must not collide with a different account.
        if (!normalized.equals(printer.getEmail())
                && userRepository.existsByEmail(normalized)) {
            throw new DuplicateEmailException(normalized);
        }
        printer.setFullName(fullName.trim());
        printer.setEmail(normalized);
        printer.setPhone(phone);
        userRepository.save(printer);

        log.info("PRINTER_UPDATED printer={} by={}", printer.getEmail(), actor);
        return printer;
    }

    /**
     * Suspend or restore access.
     *
     * ⚠️ THIS IS THE CONTROL THE WHOLE ROLE RESTS ON.
     *
     * A producer is typically an outside contractor. Their access ends here,
     * in one act, the moment a contract does — which is the argument for
     * giving them an account at all rather than sending them files.
     */
    @Transactional
    public User setEnabled(Long id, boolean enabled, String actor) {
        User printer = findPrinter(id);
        printer.setEnabled(enabled);
        userRepository.save(printer);

        log.info("PRINTER_{} printer={} by={}",
                enabled ? "ENABLED" : "DISABLED", printer.getEmail(), actor);
        return printer;
    }

    /**
     * Two-tier delete. Returns which outcome happened so the API can tell the
     * administrator whether the account was removed or archived.
     */
    @Transactional
    public DeleteOutcome delete(Long id, String actor) {
        User printer = findPrinter(id);

        if (!runRepository.findByPrintedByOrderByPrintedAtDesc(
                id, org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty()) {
            // Has production history → archive, never destroy.
            printer.setEnabled(false);
            userRepository.save(printer);
            log.info("PRINTER_ARCHIVED printer={} by={} (has production history)",
                    printer.getEmail(), actor);
            return DeleteOutcome.ARCHIVED;
        }

        userRepository.delete(printer);
        log.info("PRINTER_DELETED printer={} by={}", printer.getEmail(), actor);
        return DeleteOutcome.DELETED;
    }

    private User findPrinter(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new PrinterNotFoundException(id));
        if (user.getRole() != UserRole.PRINTER) {
            // Guard: this endpoint manages producers only, never reviewers,
            // administrators or candidates.
            throw new PrinterNotFoundException(id);
        }
        return user;
    }
}
