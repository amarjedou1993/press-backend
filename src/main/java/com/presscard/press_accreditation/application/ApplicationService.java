package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.*;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.session.SessionStatus;
import com.presscard.press_accreditation.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The candidate's side of an application: start a draft, attach evidence,
 * submit.
 *
 * Two principles run through everything here:
 *
 *  · EVERY status change goes through transition(), which consults
 *    ApplicationStatus.allowedNext() and writes a status_history row. There
 *    is no other way to move an application, so the audit trail cannot have
 *    gaps and an unauthorised transition cannot be written by accident.
 *
 *  · OWNERSHIP is checked on every operation. A candidate touching another
 *    candidate's file gets 404, not 403 — revealing that an application
 *    exists is itself a disclosure.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger("APPLICATION_AUDIT");

    private final ApplicationRepository applicationRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final StatusHistoryRepository historyRepository;
    private final SessionRepository sessionRepository;
    private final FileStorageService fileStorage;
    private final SubmissionGate submissionGate;
    private final CompletenessService completenessService;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationDocumentRepository documentRepository,
                              StatusHistoryRepository historyRepository,
                              SessionRepository sessionRepository,
                              FileStorageService fileStorage,
                              SubmissionGate submissionGate,
                              CompletenessService completenessService) {
        this.applicationRepository = applicationRepository;
        this.documentRepository = documentRepository;
        this.historyRepository = historyRepository;
        this.sessionRepository = sessionRepository;
        this.fileStorage = fileStorage;
        this.submissionGate = submissionGate;
        this.completenessService = completenessService;
    }

    /* ══ reading ══════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Application getOwned(Long applicationId, Long candidateId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        if (!application.getCandidateId().equals(candidateId)) {
            // 404 rather than 403: confirming existence is itself disclosure.
            throw new ApplicationNotFoundException(applicationId);
        }
        return application;
    }

    @Transactional(readOnly = true)
    public List<Application> listForCandidate(Long candidateId) {
        return applicationRepository.findByCandidateIdOrderByCreatedAtDesc(candidateId);
    }

    @Transactional(readOnly = true)
    public List<StatusHistory> timeline(Long applicationId, Long candidateId) {
        getOwned(applicationId, candidateId);
        return historyRepository.findByApplicationIdOrderByCreatedAtAsc(applicationId);
    }

    @Transactional(readOnly = true)
    public List<ApplicationDocument> documents(Long applicationId, Long candidateId) {
        getOwned(applicationId, candidateId);
        return documentRepository.findByApplicationIdOrderByUploadedAtAsc(applicationId);
    }

    /* ══ creating a draft ═════════════════════════════════════ */

    /**
     * Start (or resume) the candidate's application for a session.
     *
     * Idempotent by design: if a draft already exists it is returned rather
     * than rejected, so a candidate who navigates back to the wizard resumes
     * instead of hitting an error. Attempting to change category after
     * submission is refused.
     */
    @Transactional
    public Application startOrResume(Long candidateId, Long sessionId, Long categoryId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotOpenException("Session introuvable."));

        if (session.getStatus() != SessionStatus.RECEIVING) {
            throw new SessionNotOpenException(
                    "Cette session n'accepte pas de candidatures actuellement.");
        }

        var existing = applicationRepository.findByCandidateIdAndSessionId(candidateId, sessionId);
        if (existing.isPresent()) {
            Application application = existing.get();
            if (application.getStatus() != ApplicationStatus.DRAFT) {
                throw new ApplicationAlreadySubmittedException(
                        "Votre candidature pour cette session a déjà été soumise.");
            }
            // Still a draft: allow changing category before submission.
            if (!application.getCategoryId().equals(categoryId)) {
                application.setCategoryId(categoryId);
                applicationRepository.save(application);
                log.info("APPLICATION_CATEGORY_CHANGED id={} category={} by={}",
                        application.getId(), categoryId, candidateId);
            }
            return application;
        }

        Application application = Application.builder()
                .candidateId(candidateId)
                .sessionId(sessionId)
                .categoryId(categoryId)
                .status(ApplicationStatus.DRAFT)
                .build();
        applicationRepository.save(application);

        // The timeline starts here, with no "from" state.
        historyRepository.save(StatusHistory.builder()
                .applicationId(application.getId())
                .fromStatus(null)
                .toStatus(ApplicationStatus.DRAFT)
                .actorId(candidateId)
                .build());

        log.info("APPLICATION_CREATED id={} session={} category={} candidate={}",
                application.getId(), sessionId, categoryId, candidateId);
        return application;
    }

    /* ══ evidence ═════════════════════════════════════════════ */

    /** Attach an uploaded FILE (contract, work certificate). */
    @Transactional
    public ApplicationDocument attachFile(Long applicationId, Long candidateId,
                                          DocumentType docType, MultipartFile file) {
        Application application = requireEditable(applicationId, candidateId);

        if (!docType.isFile()) {
            throw new InvalidFileException(
                    "%s se fournit sous forme de lien, pas de fichier."
                            .formatted(docType.labelFr()));
        }

        String storedPath = fileStorage.store(file, applicationId);

        ApplicationDocument document = ApplicationDocument.builder()
                .applicationId(applicationId)
                .docType(docType)
                .kind(DocumentType.Kind.FILE)
                .filePath(storedPath)
                .build();
        documentRepository.save(document);

        log.info("DOCUMENT_ATTACHED application={} type={} kind=FILE by={}",
                applicationId, docType, candidateId);
        return document;
    }

    /** Attach a typed LINK (website, published article). */
    @Transactional
    public ApplicationDocument attachLink(Long applicationId, Long candidateId,
                                          DocumentType docType, String url) {
        Application application = requireEditable(applicationId, candidateId);

        if (docType.isFile()) {
            throw new InvalidFileException(
                    "%s se fournit sous forme de fichier, pas de lien."
                            .formatted(docType.labelFr()));
        }
        if (url == null || url.isBlank()) {
            throw new InvalidFileException("L'adresse du lien est requise.");
        }

        ApplicationDocument document = ApplicationDocument.builder()
                .applicationId(applicationId)
                .docType(docType)
                .kind(DocumentType.Kind.LINK)
                .url(url.trim())
                .build();
        documentRepository.save(document);

        log.info("DOCUMENT_ATTACHED application={} type={} kind=LINK by={}",
                applicationId, docType, candidateId);
        return document;
    }

    /** Remove a piece of evidence the candidate attached. */
    @Transactional
    public void removeDocument(Long applicationId, Long candidateId, Long documentId) {
        requireEditable(applicationId, candidateId);

        ApplicationDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getApplicationId().equals(applicationId)) {
            throw new DocumentNotFoundException(documentId);
        }

        if (document.getFilePath() != null) {
            fileStorage.delete(document.getFilePath());
        }
        documentRepository.delete(document);

        log.info("DOCUMENT_REMOVED application={} document={} by={}",
                applicationId, documentId, candidateId);
    }

    /* ══ the checklist and the submission ═════════════════════ */

    /** What the wizard shows: every condition, met or not. */
    @Transactional(readOnly = true)
    public SubmissionGate.GateResult checkReadiness(Long applicationId, Long candidateId) {
        return submissionGate.evaluate(getOwned(applicationId, candidateId));
    }

    /**
     * Submit for examination. The gate decides; this method only records the
     * transition. Refusal returns EVERY unmet condition so the candidate can
     * fix them in one pass.
     */
    @Transactional
    public Application submit(Long applicationId, Long candidateId) {
        Application application = getOwned(applicationId, candidateId);

        if (application.getStatus() != ApplicationStatus.DRAFT) {
            throw new ApplicationAlreadySubmittedException(
                    "Cette candidature a déjà été soumise.");
        }

        SubmissionGate.GateResult gate = submissionGate.evaluate(application);
        if (!gate.allowed()) {
            throw new SubmissionRefusedException(gate.blockers());
        }

        application.setSubmittedAt(OffsetDateTime.now());
        transition(application, ApplicationStatus.UNDER_REVIEW, candidateId, null);

        log.info("APPLICATION_SUBMITTED id={} session={} candidate={}",
                applicationId, application.getSessionId(), candidateId);
        return application;
    }

    /* ══ the single mutator ═══════════════════════════════════ */

    /**
     * The ONLY way an application's status changes. Validates the transition
     * against the state machine and writes the audit row in the same
     * transaction, so history can never drift from state.
     */
    @Transactional
    public Application transition(Application application, ApplicationStatus target,
                                  Long actorId, String justification) {
        ApplicationStatus current = application.getStatus();
        if (!current.canTransitionTo(target)) {
            throw new InvalidApplicationTransitionException(
                    "Transition impossible : %s → %s.".formatted(current, target));
        }

        application.setStatus(target);
        applicationRepository.save(application);

        historyRepository.save(StatusHistory.builder()
                .applicationId(application.getId())
                .fromStatus(current)
                .toStatus(target)
                .actorId(actorId)
                .justification(justification)
                .build());

        log.info("APPLICATION_TRANSITION id={} {}->{} actor={}",
                application.getId(), current, target, actorId);
        return application;
    }

    /* ══ helpers ══════════════════════════════════════════════ */

    /** Owned, and in a state the candidate may still edit. */
    private Application requireEditable(Long applicationId, Long candidateId) {
        Application application = getOwned(applicationId, candidateId);
        if (!application.getStatus().isEditableByCandidate()) {
            throw new ApplicationNotEditableException(
                    "Cette candidature ne peut plus être modifiée (%s)."
                            .formatted(application.getStatus().labelFr()));
        }
        return application;
    }
}
