//package com.presscard.press_accreditation.review;
//
//import com.presscard.press_accreditation.application.Application;
//import com.presscard.press_accreditation.config.AppProperties;
//import com.presscard.press_accreditation.category.PressCategory;
//import com.presscard.press_accreditation.category.PressCategoryRepository;
//import com.presscard.press_accreditation.document.*;
//import com.presscard.press_accreditation.error.DocumentNotFoundException;
//import com.presscard.press_accreditation.profile.CandidateProfile;
//import com.presscard.press_accreditation.profile.CandidateProfileRepository;
//import com.presscard.press_accreditation.review.ReviewDtos.*;
//import com.presscard.press_accreditation.storage.FileStorageService;
//import com.presscard.press_accreditation.storage.PhotoStorageService;
//import com.presscard.press_accreditation.user.User;
//import com.presscard.press_accreditation.user.UserRepository;
//import jakarta.validation.Valid;
//import org.springframework.core.io.Resource;
//import org.springframework.core.io.UrlResource;
//import org.springframework.http.*;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.bind.annotation.*;
//
//import java.io.IOException;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.security.Principal;
//import java.time.OffsetDateTime;
//import java.time.temporal.ChronoUnit;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//
///**
// * The commission's API.
// *
// * REVIEWER-gated, but note what a reviewer may see: the candidate's full
// * identity and photograph. Anonymised review is a real fairness mechanism in
// * some processes — but here the commission is judging whether a SPECIFIC
// * person is entitled to a credential bearing their face and name, and the
// * photograph itself must be judged fit for printing. Anonymity would remove
// * the very things being verified.
// */
//@RestController
//@RequestMapping("/api/reviewer")
//@PreAuthorize("hasRole('REVIEWER')")
//public class ReviewController {
//
//    private final ReviewService reviewService;
//    private final ReviewDecisionRepository decisionRepository;
//    private final ApplicationDocumentRepository documentRepository;
//    private final PressCategoryRepository categoryRepository;
//    private final CompletenessService completenessService;
//    private final CandidateProfileRepository profileRepository;
//    private final UserRepository userRepository;
//    private final FileStorageService fileStorage;
//    private final PhotoStorageService photoStorage;
//    private final AppProperties props;
//
//    public ReviewController(ReviewService reviewService,
//                            ReviewDecisionRepository decisionRepository,
//                            ApplicationDocumentRepository documentRepository,
//                            PressCategoryRepository categoryRepository,
//                            CompletenessService completenessService,
//                            CandidateProfileRepository profileRepository,
//                            UserRepository userRepository,
//                            FileStorageService fileStorage,
//                            PhotoStorageService photoStorage,
//                            AppProperties props) {
//        this.reviewService = reviewService;
//        this.decisionRepository = decisionRepository;
//        this.documentRepository = documentRepository;
//        this.categoryRepository = categoryRepository;
//        this.completenessService = completenessService;
//        this.profileRepository = profileRepository;
//        this.userRepository = userRepository;
//        this.fileStorage = fileStorage;
//        this.photoStorage = photoStorage;
//        this.props = props;
//    }
//
//    /* ══ the pool ══ */
//
//    @GetMapping("/pool")
//    public List<PoolItemResponse> pool() {
//        return reviewService.pool().stream().map(this::toPoolItem).toList();
//    }
//
//    @GetMapping("/my-files")
//    public List<PoolItemResponse> myFiles(Principal principal) {
//        return reviewService.myClaims(reviewerId(principal)).stream()
//                .map(this::toPoolItem).toList();
//    }
//
//    /* ══ claiming ══ */
//
//    @PostMapping("/{id}/claim")
//    public ExaminationResponse claim(@PathVariable Long id, Principal principal) {
//        Long me = reviewerId(principal);
//        reviewService.claim(id, me);
//        return examine(id, principal);
//    }
//
//    @PostMapping("/{id}/release")
//    public ExaminationResponse release(@PathVariable Long id, Principal principal) {
//        Long me = reviewerId(principal);
//        reviewService.release(id, me, false);
//        return examine(id, principal);
//    }
//
//    /* ══ examination ══ */
//
//    /** Everything about one dossier, in a single call. */
//    @GetMapping("/{id}")
//    @Transactional(readOnly = true)
//    public ExaminationResponse examine(@PathVariable Long id, Principal principal) {
//        Long me = reviewerId(principal);
//        Application application = reviewService.findForReview(id);
//
//        User candidate = userRepository.findById(application.getCandidateId()).orElseThrow();
//        CandidateProfile profile = profileRepository.findById(candidate.getId()).orElse(null);
//        User holder = application.getClaimedBy() == null
//                ? null
//                : userRepository.findById(application.getClaimedBy()).orElse(null);
//
//        boolean mine = me.equals(application.getClaimedBy());
//        boolean correctionAvailable =
//                application.getCorrectionCount() < props.application().maxCorrectionRounds();
//        boolean incompleteRejectionAvailable = application.getCorrectionCount() > 0;
//
//        return new ExaminationResponse(
//                application.getId(),
//                application.getStatus().name(),
//                application.getStatus().labelFr(),
//                roundName(application),
//                roundLabel(application),
//                application.getSubmittedAt(),
//                application.getCorrectionCount(),
//                props.application().maxCorrectionRounds(),
//                application.isPhotoNeedsCorrection(),
//                application.getPhotoObservation(),
//                application.getClaimedBy(),
//                holder == null ? null : holder.getFullName(),
//                application.getClaimedAt(),
//                mine,
//                new CandidateIdentityResponse(
//                        candidate.getId(), candidate.getFullName(), candidate.getEmail(),
//                        candidate.getPhone(),
//                        profile == null ? null : profile.getNni(),
//                        profile == null ? null : profile.getPassportNo(),
//                        profile == null || profile.getBirthdate() == null
//                                ? null : profile.getBirthdate().toString(),
//                        profile == null ? null : profile.getBirthplace(),
//                        profile != null && profile.getPhotoPath() != null,
//                        profile != null && profile.isPhotoAgeing()),
//                documentRepository.findByApplicationIdOrderByUploadedAtAsc(id).stream()
//                        .map(this::toDocument).toList(),
//                completenessService.evaluate(id, application.getCategoryId()),
//                decisionRepository.findByApplicationIdOrderByCreatedAtAsc(id).stream()
//                        .map(this::toHistory).toList(),
//                new AvailableActions(
//                        application.getClaimedBy() == null,
//                        mine,
//                        mine,
//                        mine && correctionAvailable,
//                        mine && incompleteRejectionAvailable,
//                        correctionAvailable ? null
//                                : "Une correction a déjà été demandée pour ce dossier.",
//                        incompleteRejectionAvailable ? null
//                                : "Un rejet pour incomplétude exige qu'une correction ait "
//                                + "d'abord été demandée au candidat."));
//    }
//
//    /** The rejection grounds, with those currently unavailable marked. */
//    @GetMapping("/{id}/rejection-grounds")
//    @Transactional(readOnly = true)
//    public List<RejectionGroundOption> rejectionGrounds(@PathVariable Long id) {
//        Application application = reviewService.findForReview(id);
//        boolean corrected = application.getCorrectionCount() > 0;
//
//        return Arrays.stream(RejectionGround.values())
//                .map(g -> new RejectionGroundOption(
//                        g.name(), g.labelFr(), g.descriptionFr(),
//                        g.requiresPriorCorrection(),
//                        !g.requiresPriorCorrection() || corrected))
//                .toList();
//    }
//
//    /* ══ the evidence ══ */
//
//    /**
//     * Stream a document. Reviewers may read any dossier's files — that is
//     * their function — but still by DOCUMENT ID through this endpoint, never
//     * by stored path, so nothing outside an application is reachable.
//     */
//    @GetMapping("/{id}/documents/{documentId}/file")
//    public ResponseEntity<Resource> documentFile(@PathVariable Long id,
//                                                 @PathVariable Long documentId)
//            throws IOException {
//        ApplicationDocument document = documentRepository.findById(documentId)
//                .orElseThrow(() -> new DocumentNotFoundException(documentId));
//        if (!document.getApplicationId().equals(id) || document.getFilePath() == null) {
//            throw new DocumentNotFoundException(documentId);
//        }
//
//        Path path = fileStorage.resolve(document.getFilePath());
//        String contentType = Files.probeContentType(path);
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.parseMediaType(
//                        contentType != null ? contentType : "application/octet-stream"))
//                .cacheControl(CacheControl.noStore().cachePrivate())
//                .body(new UrlResource(path.toUri()));
//    }
//
//    /** The candidate's photograph — the commission must judge it fit to print. */
//    @GetMapping("/{id}/photo")
//    @Transactional(readOnly = true)
//    public ResponseEntity<Resource> photo(@PathVariable Long id) throws IOException {
//        Application application = reviewService.findForReview(id);
//        CandidateProfile profile = profileRepository
//                .findById(application.getCandidateId()).orElse(null);
//
//        if (profile == null || profile.getPhotoPath() == null) {
//            return ResponseEntity.notFound().build();
//        }
//
//        Path path = photoStorage.resolve(profile.getPhotoPath());
//        if (!Files.exists(path)) {
//            return ResponseEntity.notFound().build();
//        }
//        String contentType = Files.probeContentType(path);
//
//        return ResponseEntity.ok()
//                .contentType(MediaType.parseMediaType(
//                        contentType != null ? contentType : "image/jpeg"))
//                .cacheControl(CacheControl.noStore().cachePrivate())
//                .body(new UrlResource(path.toUri()));
//    }
//
//    /* ══ the three decisions ══ */
//
//    @PostMapping("/{id}/approve")
//    public ExaminationResponse approve(@PathVariable Long id,
//                                       @Valid @RequestBody ApproveRequest request,
//                                       Principal principal) {
//        reviewService.approve(id, reviewerId(principal), request.note());
//        return examine(id, principal);
//    }
//
//    @PostMapping("/{id}/reject")
//    public ExaminationResponse reject(@PathVariable Long id,
//                                      @Valid @RequestBody RejectRequest request,
//                                      Principal principal) {
//        reviewService.reject(id, reviewerId(principal),
//                request.ground(), request.justification());
//        return examine(id, principal);
//    }
//
//    @PostMapping("/{id}/request-correction")
//    public ExaminationResponse requestCorrection(@PathVariable Long id,
//                                                 @Valid @RequestBody RequestCorrectionRequest request,
//                                                 Principal principal) {
//        List<ReviewService.DocumentFlag> flags = request.documents() == null
//                ? List.of()
//                : request.documents().stream()
//                        .map(d -> new ReviewService.DocumentFlag(d.documentId(), d.observation()))
//                        .toList();
//
//        reviewService.requestCorrection(id, reviewerId(principal), request.summary(),
//                flags, request.photoNeedsCorrection(), request.photoObservation());
//        return examine(id, principal);
//    }
//
//    /* ══ helpers ══ */
//
//    private PoolItemResponse toPoolItem(Application a) {
//        User candidate = userRepository.findById(a.getCandidateId()).orElse(null);
//        User holder = a.getClaimedBy() == null
//                ? null : userRepository.findById(a.getClaimedBy()).orElse(null);
//
//        long waiting = a.getSubmittedAt() == null ? 0
//                : ChronoUnit.DAYS.between(a.getSubmittedAt(), OffsetDateTime.now());
//
//        return new PoolItemResponse(
//                a.getId(),
//                candidate == null ? "—" : candidate.getFullName(),
//                categoryLabel(a.getCategoryId()),
//                a.getStatus().name(), a.getStatus().labelFr(),
//                roundLabel(a),
//                a.getSubmittedAt(), waiting,
//                a.getClaimedBy(), holder == null ? null : holder.getFullName(),
//                a.getClaimedAt(), a.getCorrectionCount());
//    }
//
//    private ReviewDocumentResponse toDocument(ApplicationDocument d) {
//        return new ReviewDocumentResponse(
//                d.getId(), d.getDocType().name(), d.getDocType().labelFr(),
//                d.getKind().name(), d.getUrl(),
//                d.isNeedsCorrection(), d.getObservation(),
//                d.getVersion(), d.getUploadedAt());
//    }
//
//    private DecisionHistoryEntry toHistory(ReviewDecision d) {
//        User reviewer = userRepository.findById(d.getReviewerId()).orElse(null);
//        return new DecisionHistoryEntry(
//                d.getDecision().name(), d.getDecision().labelFr(),
//                d.getRound().name(), d.getRound().labelFr(),
//                d.getRejectionGround() == null ? null : d.getRejectionGround().name(),
//                d.getRejectionGround() == null ? null : d.getRejectionGround().labelFr(),
//                d.getJustification(),
//                reviewer == null ? "—" : reviewer.getFullName(),
//                d.getCreatedAt());
//    }
//
//    private String categoryLabel(Long categoryId) {
//        return categoryRepository.findById(categoryId)
//                .map(PressCategory::getLabelFr)
//                .orElse("—");
//    }
//
//    private String roundName(Application a) {
//        return switch (a.getStatus()) {
//            case UNDER_REVIEW -> ReviewRound.INITIAL.name();
//            case UNDER_FINAL_REVIEW -> ReviewRound.FINAL.name();
//            case UNDER_RECLAMATION -> ReviewRound.RECLAMATION.name();
//            default -> null;
//        };
//    }
//
//    private String roundLabel(Application a) {
//        return switch (a.getStatus()) {
//            case UNDER_REVIEW -> ReviewRound.INITIAL.labelFr();
//            case UNDER_FINAL_REVIEW -> ReviewRound.FINAL.labelFr();
//            case UNDER_RECLAMATION -> ReviewRound.RECLAMATION.labelFr();
//            default -> "—";
//        };
//    }
//
//    private Long reviewerId(Principal principal) {
//        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
//    }
//}


package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.ApplicationDocumentRepository;
import com.presscard.press_accreditation.document.CompletenessService;
import com.presscard.press_accreditation.error.DocumentNotFoundException;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.review.ReviewDtos.*;
import com.presscard.press_accreditation.storage.FileStorageService;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * The commission's API.
 *
 * REVIEWER-gated, but note what a reviewer may SEE: the candidate's full
 * identity and photograph. Anonymised review is a real fairness mechanism in
 * some processes — but here the commission is judging whether a SPECIFIC
 * person is entitled to a credential bearing their face and name, and the
 * photograph itself must be judged fit for printing. Anonymity would remove
 * the very things being verified.
 *
 * READING is open to any reviewer; DECIDING requires the claim. That
 * asymmetry is deliberate: it lets a second opinion be sought without
 * letting two members decide the same file.
 *
 * ROUTE NOTE: /pool and /my-files are static paths and are matched BEFORE
 * /{id}, so they never collide with the dynamic segment.
 */
@RestController
@RequestMapping("/api/reviewer")
@PreAuthorize("hasRole('REVIEWER')")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReviewDecisionRepository decisionRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final PressCategoryRepository categoryRepository;
    private final CompletenessService completenessService;
    private final CandidateProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final PhotoStorageService photoStorage;
    private final AppProperties props;

    public ReviewController(ReviewService reviewService,
                            ReviewDecisionRepository decisionRepository,
                            ApplicationDocumentRepository documentRepository,
                            PressCategoryRepository categoryRepository,
                            CompletenessService completenessService,
                            CandidateProfileRepository profileRepository,
                            UserRepository userRepository,
                            FileStorageService fileStorage,
                            PhotoStorageService photoStorage,
                            AppProperties props) {
        this.reviewService = reviewService;
        this.decisionRepository = decisionRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.completenessService = completenessService;
        this.profileRepository = profileRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.photoStorage = photoStorage;
        this.props = props;
    }

    /* ══════════════ the pool ══════════════ */

    @GetMapping("/pool")
    public List<PoolItemResponse> pool(Principal principal) {
        Long me = reviewerId(principal);
        return reviewService.pool().stream().map(a -> toPoolItem(a, me)).toList();
    }

    @GetMapping("/my-files")
    public List<PoolItemResponse> myFiles(Principal principal) {
        Long me = reviewerId(principal);
        return reviewService.myClaims(me).stream().map(a -> toPoolItem(a, me)).toList();
    }

    /**
     * What this reviewer has already decided — their own accountability
     * record. A member should be able to answer "what did I decide, and
     * when" without asking an administrator.
     */
    @GetMapping("/my-decided")
    public List<PoolItemResponse> myDecided(Principal principal) {
        Long me = reviewerId(principal);
        return reviewService.myDecided(me).stream()
                .map(a -> toPoolItem(a, me)).toList();
    }

    /**
     * The session's whole picture: every submitted dossier, whatever its
     * state and whoever holds it. Reading was already permitted; this makes
     * colleagues' claims visible in the list too, so a member can see what
     * the commission as a whole is doing.
     */
    @GetMapping("/all")
    public List<PoolItemResponse> all(Principal principal) {
        Long me = reviewerId(principal);
        return reviewService.allSubmitted().stream()
                .map(a -> toPoolItem(a, me)).toList();
    }

    /* ══════════════ claiming ══════════════ */

    @PostMapping("/{id}/claim")
    public ExaminationResponse claim(@PathVariable Long id, Principal principal) {
        reviewService.claim(id, reviewerId(principal));
        return examine(id, principal);
    }

    @PostMapping("/{id}/release")
    public ExaminationResponse release(@PathVariable Long id, Principal principal) {
        reviewService.release(id, reviewerId(principal), false);
        return examine(id, principal);
    }

    /* ══════════════ examination ══════════════ */

    /**
     * Everything about one dossier, in a single call: identity, photograph
     * presence, documents, completeness, decision history, and what this
     * reviewer may do about it.
     *
     * A reviewer assembling that picture from four requests will not read it
     * all — and a partial view is how a decision gets taken on incomplete
     * information.
     */
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ExaminationResponse examine(@PathVariable Long id, Principal principal) {
        Long me = reviewerId(principal);
        Application application = reviewService.findForReview(id);

        User candidate = userRepository.findById(application.getCandidateId()).orElseThrow();
        CandidateProfile profile = profileRepository.findById(candidate.getId()).orElse(null);
        User holder = application.getClaimedBy() == null
                ? null
                : userRepository.findById(application.getClaimedBy()).orElse(null);

        boolean mine = me.equals(application.getClaimedBy());
        boolean correctionAvailable =
                application.getCorrectionCount() < props.application().maxCorrectionRounds();
        // The legal rule, computed ONCE, here: a file may not be rejected as
        // incomplete unless a correction was already requested and unanswered.
        boolean incompleteRejectionAvailable = application.getCorrectionCount() > 0;

        return new ExaminationResponse(
                application.getId(),
                application.getStatus().name(),
                application.getStatus().labelFr(),
                roundName(application),
                roundLabel(application),
                application.getSubmittedAt(),
                application.getCorrectionCount(),
                props.application().maxCorrectionRounds(),
                application.isPhotoNeedsCorrection(),
                application.getPhotoObservation(),
                application.getClaimedBy(),
                holder == null ? null : holder.getFullName(),
                application.getClaimedAt(),
                mine,
                new CandidateIdentityResponse(
                        candidate.getId(),
                        candidate.getFullName(),
                        candidate.getEmail(),
                        candidate.getPhone(),
                        profile == null ? null : profile.getNni(),
                        profile == null ? null : profile.getPassportNo(),
                        profile == null || profile.getBirthdate() == null
                                ? null : profile.getBirthdate().toString(),
                        profile == null ? null : profile.getBirthplace(),
                        profile != null && profile.getPhotoPath() != null,
                        profile != null && profile.isPhotoAgeing()),
                documentRepository.findByApplicationIdOrderByUploadedAtAsc(id).stream()
                        .map(this::toDocument).toList(),
                completenessService.evaluate(id, application.getCategoryId()),
                decisionRepository.findByApplicationIdOrderByCreatedAtAsc(id).stream()
                        .map(this::toHistory).toList(),
                new AvailableActions(
                        application.getClaimedBy() == null,
                        mine,
                        mine,
                        mine && correctionAvailable,
                        mine && incompleteRejectionAvailable,
                        correctionAvailable ? null
                                : "Une correction a déjà été demandée pour ce dossier.",
                        incompleteRejectionAvailable ? null
                                : "Un rejet pour incomplétude exige qu'une correction ait "
                                  + "d'abord été demandée au candidat."));
    }

    /**
     * The rejection grounds, with those currently unavailable MARKED rather
     * than omitted — a reviewer should see that INCOMPLETE_FILE exists and
     * why they may not use it yet, not wonder where it went.
     */
    @GetMapping("/{id}/rejection-grounds")
    @Transactional(readOnly = true)
    public List<RejectionGroundOption> rejectionGrounds(@PathVariable Long id) {
        Application application = reviewService.findForReview(id);
        boolean corrected = application.getCorrectionCount() > 0;

        return Arrays.stream(RejectionGround.values())
                .map(g -> new RejectionGroundOption(
                        g.name(), g.labelFr(), g.descriptionFr(),
                        g.requiresPriorCorrection(),
                        !g.requiresPriorCorrection() || corrected))
                .toList();
    }

    /* ══════════════ the evidence ══════════════ */

    /**
     * Stream a document. Reviewers may read any submitted dossier's files —
     * that is their function — but still by DOCUMENT ID through this
     * endpoint, never by stored path, and the document must belong to the
     * application in the URL. Nothing outside an application is reachable.
     */
    @GetMapping("/{id}/documents/{documentId}/file")
    public ResponseEntity<Resource> documentFile(@PathVariable Long id,
                                                 @PathVariable Long documentId)
            throws IOException {
        ApplicationDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));

        if (!document.getApplicationId().equals(id) || document.getFilePath() == null) {
            throw new DocumentNotFoundException(documentId);
        }

        Path path = fileStorage.resolve(document.getFilePath());
        if (!Files.exists(path)) {
            throw new DocumentNotFoundException(documentId);
        }
        String contentType = Files.probeContentType(path);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        contentType != null ? contentType : "application/octet-stream"))
                // Evidence in an accreditation file: never cached by a proxy.
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new UrlResource(path.toUri()));
    }

    /** The candidate's photograph — the commission must judge it fit to print. */
    @GetMapping("/{id}/photo")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> photo(@PathVariable Long id) throws IOException {
        Application application = reviewService.findForReview(id);
        CandidateProfile profile = profileRepository
                .findById(application.getCandidateId()).orElse(null);

        if (profile == null || profile.getPhotoPath() == null) {
            return ResponseEntity.notFound().build();
        }

        Path path = photoStorage.resolve(profile.getPhotoPath());
        if (!Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        String contentType = Files.probeContentType(path);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        contentType != null ? contentType : "image/jpeg"))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new UrlResource(path.toUri()));
    }

    /* ══════════════ the three decisions ══════════════ */

    @PostMapping("/{id}/approve")
    public ExaminationResponse approve(@PathVariable Long id,
                                       @Valid @RequestBody ApproveRequest request,
                                       Principal principal) {
        reviewService.approve(id, reviewerId(principal), request.note());
        return examine(id, principal);
    }

    @PostMapping("/{id}/reject")
    public ExaminationResponse reject(@PathVariable Long id,
                                      @Valid @RequestBody RejectRequest request,
                                      Principal principal) {
        reviewService.reject(id, reviewerId(principal),
                request.ground(), request.justification());
        return examine(id, principal);
    }

    @PostMapping("/{id}/request-correction")
    public ExaminationResponse requestCorrection(
            @PathVariable Long id,
            @Valid @RequestBody RequestCorrectionRequest request,
            Principal principal) {

        List<ReviewService.DocumentFlag> flags = request.documents() == null
                ? List.of()
                : request.documents().stream()
                .map(d -> new ReviewService.DocumentFlag(
                        d.documentId(), d.observation()))
                .toList();

        reviewService.requestCorrection(id, reviewerId(principal), request.summary(),
                flags, request.photoNeedsCorrection(), request.photoObservation());
        return examine(id, principal);
    }

    /* ══════════════ helpers ══════════════ */

//    private PoolItemResponse toPoolItem(Application a) {
//        User candidate = userRepository.findById(a.getCandidateId()).orElse(null);
//        User holder = a.getClaimedBy() == null
//                ? null : userRepository.findById(a.getClaimedBy()).orElse(null);
//
//        // The queue's fairness signal: how long this candidate has waited.
//        long waiting = a.getSubmittedAt() == null ? 0
//                : ChronoUnit.DAYS.between(a.getSubmittedAt(), OffsetDateTime.now());
//
//        return new PoolItemResponse(
//                a.getId(),
//                candidate == null ? "—" : candidate.getFullName(),
//                categoryLabel(a.getCategoryId()),
//                a.getStatus().name(),
//                a.getStatus().labelFr(),
//                roundLabel(a),
//                a.getSubmittedAt(),
//                waiting,
//                a.getClaimedBy(),
//                holder == null ? null : holder.getFullName(),
//                a.getClaimedAt(),
//                a.getCorrectionCount());
//    }

    private PoolItemResponse toPoolItem(Application a, Long viewerId) {
        User candidate = userRepository.findById(a.getCandidateId()).orElse(null);
        User holder = a.getClaimedBy() == null
                ? null : userRepository.findById(a.getClaimedBy()).orElse(null);

        // The queue's fairness signal: how long this candidate has waited.
        long waiting = a.getSubmittedAt() == null ? 0
                : ChronoUnit.DAYS.between(a.getSubmittedAt(), OffsetDateTime.now());

        // The viewer's OWN decision on this file, if any — the "Traités" tab
        // shows the outcome, not merely that the file was touched.
        ReviewDecision mine = decisionRepository
                .findByApplicationIdAndReviewerIdOrderByCreatedAtDesc(a.getId(), viewerId)
                .stream().findFirst().orElse(null);

        return new PoolItemResponse(
                a.getId(),
                candidate == null ? "—" : candidate.getFullName(),
                categoryLabel(a.getCategoryId()),
                a.getStatus().name(),
                a.getStatus().labelFr(),
                roundLabel(a),
                a.getSubmittedAt(),
                waiting,
                a.getClaimedBy(),
                holder == null ? null : holder.getFullName(),
                a.getClaimedAt(),
                a.getCorrectionCount(),
                mine == null ? null : mine.getDecision().name(),
                mine == null ? null : mine.getDecision().labelFr(),
                mine == null ? null : mine.getCreatedAt());
    }

    private ReviewDocumentResponse toDocument(ApplicationDocument d) {
        return new ReviewDocumentResponse(
                d.getId(),
                d.getDocType().name(),
                d.getDocType().labelFr(),
                d.getKind().name(),
                d.getUrl(),
                d.isNeedsCorrection(),
                d.getObservation(),
                d.getVersion(),
                d.getUploadedAt());
    }

    private DecisionHistoryEntry toHistory(ReviewDecision d) {
        User reviewer = userRepository.findById(d.getReviewerId()).orElse(null);
        return new DecisionHistoryEntry(
                d.getDecision().name(),
                d.getDecision().labelFr(),
                d.getRound().name(),
                d.getRound().labelFr(),
                d.getRejectionGround() == null ? null : d.getRejectionGround().name(),
                d.getRejectionGround() == null ? null : d.getRejectionGround().labelFr(),
                d.getJustification(),
                reviewer == null ? "—" : reviewer.getFullName(),
                d.getCreatedAt());
    }

    private String categoryLabel(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .map(PressCategory::getLabelFr)
                .orElse("—");
    }

    private String roundName(Application a) {
        return switch (a.getStatus()) {
            case UNDER_REVIEW -> ReviewRound.INITIAL.name();
            case UNDER_FINAL_REVIEW -> ReviewRound.FINAL.name();
            case UNDER_RECLAMATION -> ReviewRound.RECLAMATION.name();
            default -> null;
        };
    }

    private String roundLabel(Application a) {
        return switch (a.getStatus()) {
            case UNDER_REVIEW -> ReviewRound.INITIAL.labelFr();
            case UNDER_FINAL_REVIEW -> ReviewRound.FINAL.labelFr();
            case UNDER_RECLAMATION -> ReviewRound.RECLAMATION.labelFr();
            default -> "—";
        };
    }

    private Long reviewerId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}