package com.presscard.press_accreditation.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(UserRole role);

    long countByRole(UserRole role);

    /** Reviewer management list — most recent first. */
    List<User> findByRoleOrderByCreatedAtDesc(UserRole role);
}
