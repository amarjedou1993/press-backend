package com.presscard.press_accreditation.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {

//    List<ApplicationDocument> findByApplicationIdOrderByUploadedAtAsc(Long applicationId);

    /** Only documents NOT flagged for correction count towards completeness. */
    List<ApplicationDocument> findByApplicationIdAndNeedsCorrectionFalse(Long applicationId);

    List<ApplicationDocument> findByApplicationIdAndDocType(Long applicationId, DocumentType docType);

    long countByApplicationIdAndDocType(Long applicationId, DocumentType docType);

    /** Week 5: the pieces a reviewer asked to be replaced. */
//    List<ApplicationDocument> findByApplicationIdAndNeedsCorrectionTrue(Long applicationId);

    /**
     * The CURRENT documents only. Every completeness check must use this:
     * counting superseded rows would credit a candidate twice for the same
     * piece of evidence.
     */
    @Query("""
           SELECT d FROM ApplicationDocument d
           WHERE d.applicationId = :applicationId AND d.supersededAt IS NULL
           ORDER BY d.uploadedAt ASC
           """)
    List<ApplicationDocument> findCurrentByApplicationId(@Param("applicationId") Long applicationId);

    /** Everything, including superseded versions — the reviewer's history view. */
    List<ApplicationDocument> findByApplicationIdOrderByUploadedAtAsc(Long applicationId);

    /** Flagged pieces awaiting a correction. */
    List<ApplicationDocument> findByApplicationIdAndNeedsCorrectionTrue(Long applicationId);
   }
