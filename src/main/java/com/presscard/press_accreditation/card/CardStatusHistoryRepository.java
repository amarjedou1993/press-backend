package com.presscard.press_accreditation.card;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardStatusHistoryRepository extends JpaRepository<CardStatusHistory, Long> {

    List<CardStatusHistory> findByCardIdOrderByCreatedAtDesc(Long cardId);
}
