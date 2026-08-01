package com.presscard.press_accreditation.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecialisationRepository extends JpaRepository<Specialisation, Long> {

    /** What a candidate may choose from, in HAPA's own order. */
    List<Specialisation> findByActiveTrueOrderByDisplayOrderAsc();

    Optional<Specialisation> findByCode(String code);
}
