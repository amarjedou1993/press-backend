package com.presscard.press_accreditation.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Used by AdminInitializer: create the first SUPER_ADMIN only if none exists. */
    boolean existsByRole(UserRole role);
}
