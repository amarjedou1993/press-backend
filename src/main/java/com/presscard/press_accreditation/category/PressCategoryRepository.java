package com.presscard.press_accreditation.category;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PressCategoryRepository extends JpaRepository<PressCategory, Long> {

    Optional<PressCategory> findByCode(String code);
}