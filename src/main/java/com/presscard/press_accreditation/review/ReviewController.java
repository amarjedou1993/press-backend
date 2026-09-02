package com.presscard.press_accreditation.review;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationStatus;
import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.config.AppProperties;
import com.presscard.press_accreditation.document.ApplicationDocument;
import com.presscard.press_accreditation.document.ApplicationDocumentRepository;
import com.presscard.press_accreditation.document.CompletenessService;
import com.presscard.press_accreditation.error.DocumentNotFoundException;
import com.presscard.press_accreditation.objection.Objection;
import com.presscard.press_accreditation.objection.ObjectionReason;
import com.presscard.press_accreditation.objection.ObjectionService;
import com.presscard.press_accreditation.profile.CandidateProfile;
import com.presscard.press_accreditation.profile.CandidateProfileRepository;
import com.presscard.press_accreditation.review.ReviewDtos.*;
import com.presscard.press_accreditation.session.Session;
import com.presscard.press_accreditation.session.SessionRepository;
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
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reviewer")
@PreAuthorize("hasRole('REVIEWER')")
public class ReviewController {

    private static final DateTimeFormatter SESSION_DATE =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    private final ReviewService reviewService;
    private final ObjectionService objectionService;
    private final ReviewDecisionRepository decisionRepository;
    private final ApplicationDocumentRepository documentRepository;
    private final PressCategoryRepository categoryRepository;
    private final CompletenessService completenessService;
    private final CandidateProfileRepository profileRepository;
    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorage;
    private final PhotoStorageService photoStorage;
    private final SpecialisationRepository specialisationRepository;
    private final AppProperties props;

    public ReviewController(ReviewService reviewService,
                            ObjectionService objectionService,
                            ReviewDecisionRepository decisionRepository,
                            ApplicationDocumentRepository documentRepository,
                            PressCategoryRepository categoryRepository,
                            CompletenessService completenessService,
                            CandidateProfileRepository profileRepository,
                            SessionRepository sessionRepository,
                            UserRepository userRepository,
                            FileStorageService fileStorage,
                            PhotoStorageService photoStorage,
                            SpecialisationRepository specialisationRepository,
                            AppProperties props) {
        this.reviewService = reviewService;
        this.objectionService = objectionService;
        this.decisionRepository = decisionRepository;
        this.documentRepository = documentRepository;
        this.categoryRepository = categoryRepository;
        this.completenessService = completenessService;
        this.profileRepository = profileRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.fileStorage = fileStorage;
        this.photoStorage = photoStorage;
        this.specialisationRepository = specialisationRepository;
        this.props = props;
    }

    /* ══════════════ the four lists ══════════════ */

    /** What this reviewer may take: unclaimed, and not their own rejection. */
    @GetMapping("/pool")
    public List<PoolItemResponse> pool(Principal principal) {
        Long me = reviewerId(principal);
        return toPoolItems(reviewService.pool(me), me);
    }

    /** What they must decide. */
    @GetMapping("/my-files")
    public List<PoolItemResponse> myFiles(Principal principal) {
        Long me = reviewerId(principal);
        return toPoolItems(reviewService.myClaims(me), me);
    }

    /**
     * What they have already decided — their own accountability record. A
     * member should be able to answer "what did I decide, and when" without
     * asking an administrator.
     */
    @GetMapping("/my-decided")
    public List<PoolItemResponse> myDecided(Principal principal) {
        Long me = reviewerId(principal);
        return toPoolItems(reviewService.myDecided(me), me);
    }

    /** The session's whole picture, including colleagues' claims. */
    @GetMapping("/all")
    public List<PoolItemResponse> all(Principal principal) {
        Long me = reviewerId(principal);
        return toPoolItems(reviewService.allSubmitted(), me);
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
     * presence, documents, completeness, decision history, the contestation
     * if there is one, and what this reviewer may do about it.
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
        boolean reclamation = application.getStatus() == ApplicationStatus.UNDER_RECLAMATION;

        // The legal rule, computed ONCE, here: a file may not be rejected as
        // incomplete unless a correction was already requested — a duty
        // discharged by the time a reclamation is examined.
        boolean incompleteRejectionAvailable =
                application.getCorrectionCount() > 0 || reclamation;

        // V1.3 §J — the author of the contested decision is barred.
        boolean barred = !reviewService.mayExamine(application, me);

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
                        profile != null && profile.isPhotoAgeing(),
                        application.getSpecialisationId() == null ? null
                                : specialisationRepository
                                .findById(application.getSpecialisationId())
                                .map(Specialisation::getLabelFr)
                                .orElse(null),
                        application.getInstitution()),
                documentRepository.findByApplicationIdOrderByUploadedAtAsc(id).stream()
                        .map(this::toDocument).toList(),
                completenessService.evaluate(id, application.getCategoryId()),
                decisionRepository.findByApplicationIdOrderByCreatedAtAsc(id).stream()
                        .map(this::toHistory).toList(),
                reclamation ? objectionSummary(id) : null,
                new AvailableActions(
                        application.getClaimedBy() == null && !barred,
                        mine,
                        mine,
                        mine && correctionAvailable,
                        mine && incompleteRejectionAvailable,
                        barred,
                        correctionAvailable ? null
                                : "Une correction a déjà été demandée pour ce dossier.",
                        incompleteRejectionAvailable ? null
                                : "Un rejet pour incomplétude exige qu'une correction ait "
                                  + "d'abord été demandée au candidat.",
                        barred
                                ? "Vous avez rendu la décision contestée. Le règlement impose "
                                  + "qu'une réclamation soit examinée par un autre membre de la "
                                  + "commission."
                                : null));
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
        boolean discharged = application.getCorrectionCount() > 0
                || application.getStatus() == ApplicationStatus.UNDER_RECLAMATION;

        return Arrays.stream(RejectionGround.values())
                .map(g -> new RejectionGroundOption(
                        g.name(), g.labelFr(), g.descriptionFr(),
                        g.requiresPriorCorrection(),
                        !g.requiresPriorCorrection() || discharged))
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

    /**
     * The contestation and the decision it contests, together.
     *
     * A second reviewer who sees only the objection is re-examining in the
     * dark; one who sees only the rejection has no idea what is disputed.
     */
    private ObjectionSummary objectionSummary(Long applicationId) {
        Objection filed = objectionService.findByApplication(applicationId);
        if (filed == null) {
            return null;
        }
        ObjectionReason reason = objectionService.reason(filed.getReasonId());
        ReviewDecision contested = filed.getContestedDecisionId() == null
                ? null
                : decisionRepository.findById(filed.getContestedDecisionId()).orElse(null);
        User author = contested == null
                ? null
                : userRepository.findById(contested.getReviewerId()).orElse(null);

        return new ObjectionSummary(
                reason == null ? null : reason.getLabelFr(),
                reason == null ? null : reason.getLabelAr(),
                filed.getArgument(),
                filed.getCreatedAt(),
                contested == null ? null : contested.getJustification(),
                contested == null || contested.getRejectionGround() == null
                        ? null : contested.getRejectionGround().labelFr(),
                author == null ? null : author.getFullName());
    }

    /**
     * Map a list of dossiers with EVERY lookup batched.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ FIVE QUERIES, WHATEVER THE LIST'S LENGTH.
     *
     * It was four PER ROW — candidate, claim-holder, decision, category. A
     * page of twenty-four dossiers therefore made about a hundred sequential
     * round trips, all on one pooled connection, holding it for over a
     * second. With a pool of ten, that capped this endpoint near seven
     * requests a second.
     *
     * The four maps below are built once. Nothing inside the mapping touches
     * a repository, and that is the property worth keeping.
     * ───────────────────────────────────────────────────────────────────
     */
    private List<PoolItemResponse> toPoolItems(List<Application> applications, Long viewerId) {
        if (applications.isEmpty()) {
            return List.of();
        }

        List<Long> applicationIds = applications.stream()
                .map(Application::getId).toList();

        // Candidates and claim-holders together: the two sets overlap rarely,
        // but one findAllById is cheaper than two round trips.
        Set<Long> userIds = new HashSet<>();
        applications.forEach(a -> {
            userIds.add(a.getCandidateId());
            if (a.getClaimedBy() != null) userIds.add(a.getClaimedBy());
        });

        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        // Both catalogues are a handful of rows that cannot change during a
        // single request.
        Map<Long, PressCategory> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(PressCategory::getId, Function.identity()));

        Map<Long, Session> sessions = sessionRepository.findAll().stream()
                .collect(Collectors.toMap(Session::getId, Function.identity()));

        Map<Long, ReviewDecision> myDecisions = decisionRepository
                .findLatestByReviewerForApplications(viewerId, applicationIds).stream()
                .collect(Collectors.toMap(
                        ReviewDecision::getApplicationId, Function.identity()));

        return applications.stream()
                .map(a -> toPoolItem(a, users, categories, sessions, myDecisions))
                .toList();
    }

    /**
     * ⚠️ NO REPOSITORY CALLS HERE, and none may be added.
     *
     * A lookup placed in this method is a lookup per row, and its cost will
     * not show in a code review — it will show in a load test months later,
     * as a page that takes two seconds for reasons nobody can point at.
     *
     * Anything new is batched in toPoolItems above and passed in.
     */
    private PoolItemResponse toPoolItem(Application a,
                                        Map<Long, User> users,
                                        Map<Long, PressCategory> categories,
                                        Map<Long, Session> sessions,
                                        Map<Long, ReviewDecision> myDecisions) {
        User candidate = users.get(a.getCandidateId());
        User holder = a.getClaimedBy() == null ? null : users.get(a.getClaimedBy());

        // The queue's fairness signal: how long this candidate has waited.
        long waiting = a.getSubmittedAt() == null ? 0
                : ChronoUnit.DAYS.between(a.getSubmittedAt(), OffsetDateTime.now());

        // The viewer's OWN decision on this file, if any — the "Traités" tab
        // shows the outcome, not merely that the file was touched.
        ReviewDecision mine = myDecisions.get(a.getId());

        PressCategory category = categories.get(a.getCategoryId());
        Session session = sessions.get(a.getSessionId());

        return new PoolItemResponse(
                a.getId(),
                candidate == null ? "—" : candidate.getFullName(),
                category == null ? "—" : category.getLabelFr(),
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
                mine == null ? null : mine.getCreatedAt(),
                sessionLabel(session));
    }

    /**
     * "Session du 12 mars 2026".
     *
     * ⚠️ Read only in "Mes décisions", the one scope that crosses sessions.
     * In the working queue every row would carry the same label — noise
     * repeating the context instead of adding to it. The screen decides; this
     * only supplies.
     */
    private static String sessionLabel(Session session) {
        return session == null || session.getStartDate() == null
                ? null
                : "Session du " + session.getStartDate().format(SESSION_DATE);
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