package com.presscard.press_accreditation.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, Long> {

    /** The rules for one press category (seeded reference data). */
    List<DocumentRequirement> findByCategoryId(Long categoryId);

    /** What a renewal must supply — a subset of the category's rules. */
    List<DocumentRequirement> findByCategoryIdAndRequiredForRenewalTrue(Long categoryId);
}
