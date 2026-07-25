package com.presscard.press_accreditation.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StatusHistoryRepository extends JpaRepository<StatusHistory, Long> {

    /** The candidate's timeline, oldest first. */
    List<StatusHistory> findByApplicationIdOrderByCreatedAtAsc(Long applicationId);
}
