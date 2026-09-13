package com.presscard.press_accreditation.institutional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {

    Optional<Institution> findByCode(String code);

    List<Institution> findByActiveTrueOrderByNameFrAsc();
}
