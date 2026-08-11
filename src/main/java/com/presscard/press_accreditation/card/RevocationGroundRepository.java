package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RevocationGroundRepository extends JpaRepository<RevocationGround, Long> {

    List<RevocationGround> findByActiveTrueOrderByDisplayOrderAsc();

    Optional<RevocationGround> findByCode(String code);
}
