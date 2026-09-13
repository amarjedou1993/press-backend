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

    /** Active commission members — used to check the objection right can be honoured. */
    List<User> findByRoleAndEnabledTrue(UserRole role);

    /**
     * An institution's account, if it has one.
     *
     * ⚠️ ONE PER BODY, and the CHECK constraint on users makes that the only
     * sensible shape: an INSTITUTION account must name an institution, and no
     * other role may. Returning Optional rather than a list says so.
     *
     * The alternative — loading every institution account and filtering in
     * Java — is what the register controller did first. Harmless at two
     * bodies, and the shape that becomes a full-table read at twenty.
     */
    Optional<User> findByRoleAndInstitutionId(UserRole role, Long institutionId);
}
