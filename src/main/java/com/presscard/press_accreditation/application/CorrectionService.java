package com.presscard.press_accreditation.application;

import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.ApplicationDocumentRepository;
import com.presscard.press_accreditation.document.DocumentType;
import com.presscard.press_accreditation.email.EmailService;
import com.presscard.press_accreditation.error.*;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
import com.presscard.press_accreditation.storage.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The candidate's answer to a correction request.
 *
 * FOUR RULES.
 *
 * 1. ONLY FLAGGED PIECES MAY BE REPLACED. A correction round answers what the
 *    commission asked about; it is not an opportunity to rebuild the dossier.
 *    Allowing anything else would let a candidate swap evidence the reviewer
 *    had already accepted, and the reviewer would have no way to know.
 *
 * 2. THE ORIGINAL IS NEVER DESTROYED. A replacement is a new row; the old one
 *    is marked superseded. What was originally submitted is evidence of what
 *    the first decision was taken on, and a regulator that overwrites it
 *    cannot later show its work.
 *
 * 3. EVERYTHING FLAGGED MUST BE ANSWERED BEFORE RESUBMISSION. A partial answer
 *    would return the file to a reviewer who must then re-request the same
 *    correction — but the single round has already been spent.
 *
 * 4. THE DEADLINE BINDS. Past correction_end the file can no longer be
 *    corrected, whatever the session's displayed phase, because the nightly
 *    job is about to reject it.
 */
@Service
public class CorrectionService {

    private static final Logger log = LoggerFactory.getLogger("CORRECTION_AUDIT");

    private final ApplicationRepository applicationRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final CandidateProfileRepository profileRepository;
    private final SessionRepository sessionRepository;
    private final ApplicationService applicationService;
    private final FileStorageService fileStorage;
    private final EmailService emailService;

    public CorrectionService(ApplicationRepository applicationRepository,
                             ApplicationDocumentRepository documentRepository,
                             CandidateProfileRepository profileRepository,
                             SessionRepository sessionRepository,
                             ApplicationService applicationService,
                             FileStorageService fileStorage,
                             EmailService emailService) {
        this.applicationRepository = applicationRepository;
        this.documentRepository = documentRepository;
        this.profileRepository = profileRepository;
        this.sessionRepository = sessionRepository;
        this.applicationService = applicationService;
        this.fileStorage = fileStorage;
        this.emailService = emailService;
    }

    /* ══ what remains to be corrected ══════════════════════════ */

    /** One outstanding item, and whether the candidate has answered it. */
    public record OutstandingItem(
            Long documentId,
            String docType,
            String docTypeLabelFr,
            String observation,
            boolean answered
    ) {}

    public record CorrectionState(
            boolean inCorrection,
            LocalDate deadline,
            long daysRemaining,
            boolean deadlinePassed,
            List<OutstandingItem> documents,
            boolean photoNeedsCorrection,
            String photoObservation,
            boolean photoAnswered,
            boolean readyToResubmit,
            List<String> remainingFr
    ) {}

    /**
     * Everything the correction screen needs, decided HERE rather than in the
     * browser — the same principle as the submission gate: the object that
     * decides whether a resubmission is possible is the object that explains
     * what is missing.
     */
    @Transactional(readOnly = true)
    public CorrectionState state(Long applicationId, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();

        boolean inCorrection = application.getStatus() == ApplicationStatus.CORRECTION_REQUESTED;
        LocalDate deadline = session.getCorrectionEnd();
        long remaining = deadline == null ? 0
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), deadline);
        boolean passed = deadline != null && LocalDate.now().isAfter(deadline);

        List<OutstandingItem> items = documentRepository
                .findByApplicationIdAndNeedsCorrectionTrue(applicationId).stream()
                .map(d -> new OutstandingItem(
                        d.getId(),
                        d.getDocType().name(),
                        d.getDocType().labelFr(),
                        d.getObservation(),
                        // Answered = this row has been superseded by a newer one.
                        d.getSupersededAt() != null))
                .toList();

        CandidateProfile profile = profileRepository.findById(candidateId).orElse(null);
        boolean photoFlagged = application.isPhotoNeedsCorrection();
        // The photo counts as answered once it was uploaded AFTER the request.
        boolean photoAnswered = photoFlagged
                && profile != null
                && profile.getPhotoUploadedAt() != null
                && application.getCorrectionRequestedAt() != null
                && profile.getPhotoUploadedAt().isAfter(application.getCorrectionRequestedAt());

        List<String> remainingFr = new java.util.ArrayList<>();
        items.stream().filter(i -> !i.answered())
                .forEach(i -> remainingFr.add(i.docTypeLabelFr()));
        if (photoFlagged && !photoAnswered) {
            remainingFr.add("Photographie d'identité");
        }

        return new CorrectionState(
                inCorrection, deadline, Math.max(remaining, 0), passed,
                items, photoFlagged, application.getPhotoObservation(), photoAnswered,
                inCorrection && !passed && remainingFr.isEmpty(),
                List.copyOf(remainingFr));
    }

    /* ══ replacing a flagged document ══════════════════════════ */

    /**
     * Replace one flagged document. The old row is kept and marked superseded;
     * the new one carries version + 1 and clears the flag.
     */
    @Transactional
    public ApplicationDocument replaceDocument(Long applicationId, Long documentId,
                                               MultipartFile file, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        requireCorrectable(application);

        ApplicationDocument old = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!old.getApplicationId().equals(applicationId)) {
            throw new DocumentNotFoundException(documentId);
        }
        if (old.getSupersededAt() != null) {
            throw new NotCorrectableException(
                    "Cette pièce a déjà été remplacée.");
        }
        // Rule 1: only what the commission flagged.
        if (!old.isNeedsCorrection()) {
            throw new NotCorrectableException(
                    "Cette pièce n'a pas été signalée par la commission. Seules les "
                  + "pièces marquées « correction demandée » peuvent être remplacées.");
        }
        if (old.getKind() != DocumentType.Kind.FILE) {
            throw new NotCorrectableException(
                    "Cette pièce est un lien : utilisez le remplacement de lien.");
        }

        String storedPath = fileStorage.store(file, applicationId);

        ApplicationDocument replacement = documentRepository.save(
                ApplicationDocument.builder()
                        .applicationId(applicationId)
                        .docType(old.getDocType())
                        .kind(DocumentType.Kind.FILE)
                        .filePath(storedPath)
                        .needsCorrection(false)
                        .observation(null)
                        .version(old.getVersion() + 1)
                        .build());

        // Rule 2: the original survives, pointing at what replaced it.
        old.setSupersededAt(OffsetDateTime.now());
        old.setSupersededBy(replacement.getId());
        documentRepository.save(old);

        log.info("CORRECTION_DOCUMENT_REPLACED application={} old={} new={} version={}",
                applicationId, documentId, replacement.getId(), replacement.getVersion());
        return replacement;
    }

    /** Replace a flagged link. Same rules, no file. */
    @Transactional
    public ApplicationDocument replaceLink(Long applicationId, Long documentId,
                                           String url, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        requireCorrectable(application);

        ApplicationDocument old = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!old.getApplicationId().equals(applicationId) || old.getSupersededAt() != null) {
            throw new DocumentNotFoundException(documentId);
        }
        if (!old.isNeedsCorrection()) {
            throw new NotCorrectableException(
                    "Cette pièce n'a pas été signalée par la commission.");
        }

        ApplicationDocument replacement = documentRepository.save(
                ApplicationDocument.builder()
                        .applicationId(applicationId)
                        .docType(old.getDocType())
                        .kind(DocumentType.Kind.LINK)
                        .url(url.trim())
                        .needsCorrection(false)
                        .version(old.getVersion() + 1)
                        .build());

        old.setSupersededAt(OffsetDateTime.now());
        old.setSupersededBy(replacement.getId());
        documentRepository.save(old);

        log.info("CORRECTION_LINK_REPLACED application={} old={} new={}",
                applicationId, documentId, replacement.getId());
        return replacement;
    }

    /* ══ resubmission ══════════════════════════════════════════ */

    /**
     * Return the corrected dossier to the commission.
     *
     * Refuses while anything flagged remains unanswered: a partial answer
     * would land on a reviewer who must re-request the same correction, and
     * the single round allowed by the règlement has already been spent.
     */
    @Transactional
    public Application resubmit(Long applicationId, Long candidateId) {
        Application application = findOwned(applicationId, candidateId);
        requireCorrectable(application);

        CorrectionState state = state(applicationId, candidateId);
        if (!state.remainingFr().isEmpty()) {
            throw new CorrectionIncompleteException(
                    "Il reste des pièces à corriger : " + String.join(", ", state.remainingFr()));
        }

        // Clear the photo flag — it has been answered.
        if (application.isPhotoNeedsCorrection()) {
            application.setPhotoNeedsCorrection(false);
            application.setPhotoObservation(null);
        }

        applicationService.transition(application, ApplicationStatus.UNDER_FINAL_REVIEW,
                candidateId, "Corrections déposées par le candidat.");
        applicationRepository.save(application);

        emailService.sendResubmissionConfirmation(candidateId, applicationId);

        log.info("CORRECTION_RESUBMITTED application={} candidate={}",
                applicationId, candidateId);
        return application;
    }

    /* ══ guards ════════════════════════════════════════════════ */

    private Application findOwned(Long applicationId, Long candidateId) {
        Application application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ApplicationNotFoundException(applicationId));
        if (!application.getCandidateId().equals(candidateId)) {
            // 404, not 403: another candidate's dossier does not exist for you.
            throw new ApplicationNotFoundException(applicationId);
        }
        return application;
    }

    private void requireCorrectable(Application application) {
        if (application.getStatus() != ApplicationStatus.CORRECTION_REQUESTED) {
            throw new NotCorrectableException(
                    "Aucune correction n'est demandée sur ce dossier.");
        }

        Session session = sessionRepository.findById(application.getSessionId()).orElseThrow();
        // Rule 4: the deadline binds regardless of the displayed phase — the
        // nightly job is about to reject this file.
        if (session.getCorrectionEnd() != null
                && LocalDate.now().isAfter(session.getCorrectionEnd())) {
            throw new NotCorrectableException(
                    "Le délai de correction est expiré (%s). Votre dossier va être "
                  + "examiné en l'état.".formatted(session.getCorrectionEnd()));
        }
    }
}
