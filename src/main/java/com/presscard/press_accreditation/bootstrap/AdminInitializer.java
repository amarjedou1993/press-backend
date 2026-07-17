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

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(UserRole.SUPER_ADMIN)) {
            return;
        }

        User admin = User.builder()
                .email(AdminEmail())
                .passwordHash(passwordEncoder.encode(props.admin().initialPassword()))
                .role(UserRole.SUPER_ADMIN)
                .fullName("Super Admin")
                .build();
        userRepository.save(admin);

        log.warn("Bootstrap SUPER_ADMIN created: {} — change the initial password immediately.",
                admin.getEmail());
    }

    private String AdminEmail() {
        return props.admin().email().trim().toLowerCase(java.util.Locale.ROOT);
    }
}
