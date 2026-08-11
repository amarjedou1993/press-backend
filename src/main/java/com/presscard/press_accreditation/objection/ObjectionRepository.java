package com.presscard.press_accreditation.objection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObjectionRepository extends JpaRepository<Objection, Long> {

    Optional<Objection> findByApplicationId(Long applicationId);

    /** The once-only right, read rather than assumed. */
    boolean existsByApplicationId(Long applicationId);

    long countByApplicationIdIn(List<Long> applicationIds);
}
