package com.presscard.press_accreditation.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {

    List<ApplicationDocument> findByApplicationIdOrderByUploadedAtAsc(Long applicationId);

    /** Only documents NOT flagged for correction count towards completeness. */
    List<ApplicationDocument> findByApplicationIdAndNeedsCorrectionFalse(Long applicationId);

    List<ApplicationDocument> findByApplicationIdAndDocType(Long applicationId, DocumentType docType);

    long countByApplicationIdAndDocType(Long applicationId, DocumentType docType);

    /** Week 5: the pieces a reviewer asked to be replaced. */
    List<ApplicationDocument> findByApplicationIdAndNeedsCorrectionTrue(Long applicationId);
}
