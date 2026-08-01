package com.presscard.press_accreditation.objection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ObjectionReasonRepository extends JpaRepository<ObjectionReason, Long> {

    /** What the candidate may choose from, in HAPA's own order. */
    List<ObjectionReason> findByActiveTrueOrderByDisplayOrderAsc();

    Optional<ObjectionReason> findByCode(String code);
}
