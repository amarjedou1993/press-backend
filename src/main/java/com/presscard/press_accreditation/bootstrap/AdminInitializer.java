package com.presscard.press_accreditation.bootstrap;

import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Solves the bootstrap problem: the Super Admin creates all other staff
 * accounts, but nobody creates the Super Admin.
 *
 * Why a runner and not a Flyway seed: a seed migration would put a password
 * hash in the git history of a government system — a credential in version
 * control, forever. Here the credentials come from configuration: harmless
 * defaults in dev, environment variables in prod, nothing in the repo.
 *
 * Idempotent: runs at every startup, creates the admin only if no
 * SUPER_ADMIN exists yet.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE FIRST START REFUSES TO RUN WITHOUT REAL CREDENTIALS.
 *
 * In production, ADMIN_EMAIL and ADMIN_INITIAL_PASSWORD default to empty, so
 * that the password can be removed from the environment once the account
 * exists. But on the very first start, empty values would have created a
 * SUPER_ADMIN with no email and no password — and since this runner only acts
 * when no SUPER_ADMIN exists, the real one could then never be created.
 *
 * So when the account is missing, both values are required and the password
 * must be at least 12 characters; otherwise the application stops with a
 * message naming the two variables. Once the account exists, the method
 * returns before reading them, which is what makes removing the password
 * afterwards safe.
 * ───────────────────────────────────────────────────────────────────────
 */
@Component
public class AdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public AdminInitializer(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            AppProperties props) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.props = props;
    }

    /** A bootstrap credential typed by an operator, guarding a government system. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(UserRole.SUPER_ADMIN)) {
            // ⚠️ Before any read of the credentials: once the account exists,
            // they may be absent from the environment, and that is intended.
            return;
        }

        AppProperties.Admin creds = props.admin();
        String email = creds == null || creds.email() == null ? "" : creds.email().trim();
        String password = creds == null || creds.initialPassword() == null ? "" : creds.initialPassword();

        if (email.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException(
                    "No SUPER_ADMIN account exists yet: set ADMIN_EMAIL and "
                            + "ADMIN_INITIAL_PASSWORD for the first start. They may be removed "
                            + "from the environment once the account has been created.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_INITIAL_PASSWORD must be at least " + MIN_PASSWORD_LENGTH
                            + " characters.");
        }

        User admin = User.builder()
                .email(email.toLowerCase(java.util.Locale.ROOT))
                .passwordHash(passwordEncoder.encode(password))
                .role(UserRole.SUPER_ADMIN)
                .fullName("Super Admin")
                .emailVerified(true)
                .build();
        userRepository.save(admin);

        log.warn("Bootstrap SUPER_ADMIN created: {} — change the initial password immediately.",
                admin.getEmail());
    }

}
