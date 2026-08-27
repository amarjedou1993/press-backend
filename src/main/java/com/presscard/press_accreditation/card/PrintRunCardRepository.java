package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrintRunCardRepository
        extends JpaRepository<PrintRunCard, PrintRunCard.Key> {
}
