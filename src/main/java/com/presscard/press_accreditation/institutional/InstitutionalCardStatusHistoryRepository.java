package com.presscard.press_accreditation.institutional;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InstitutionalCardStatusHistoryRepository
        extends JpaRepository<InstitutionalCardStatusHistory, Long> {

    List<InstitutionalCardStatusHistory>
        findByInstitutionalCardIdOrderByCreatedAtDesc(Long institutionalCardId);
}
