package com.presscard.press_accreditation.error;

import com.presscard.press_accreditation.admin.PrinterNotFoundException;
import com.presscard.press_accreditation.admin.ReviewerNotFoundException;
import com.presscard.press_accreditation.honour.HonourCardException;
import com.presscard.press_accreditation.honour.HonourCardNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every error the API returns, in one place.
 *
 * TWO PRINCIPLES RUN THROUGH IT.
 *
 * 1. A MESSAGE THE READER CAN ACT ON. Most of these exceptions carry French
 *    text written for the person who will see it — a reviewer, a candidate, an
 *    administrator — so the handler passes it through rather than replacing it
 *    with a generic phrase. The exceptions are the ones below that would
 *    disclose something.
 *
 * 2. NOTHING INTERNAL LEAKS. Constraint names, stack traces and schema details
 *    stay in the log. The catch-all returns an incident id and nothing else,
 *    so a support request can be traced without the response having explained
 *    the system's shape to whoever caused it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /* ══════════════ 400 — malformed or invalid input ══════════════ */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
        // Field by field, so a form can render each message under its own input
        // rather than showing one banner for four problems.
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail onUnreadableBody(HttpMessageNotReadableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Malformed request body");
        pd.setDetail("The request body is not valid JSON.");
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Invalid parameter");
        pd.setDetail("Parameter '" + ex.getName() + "' has an invalid value.");
        return pd;
    }

    @ExceptionHandler(InvalidFileException.class)
    ProblemDetail onInvalidFile(InvalidFileException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Fichier invalide");
        pd.setDetail(ex.getMessage());   // already French and user-facing
        return pd;
    }

    @ExceptionHandler(InvalidTokenException.class)
    ProblemDetail onInvalidToken(InvalidTokenException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Lien invalide");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * A decision submitted without the reasoning it requires.
     *
     * A rejection without a justification is one the candidate cannot contest,
     * which makes their objection right meaningless.
     */
    @ExceptionHandler(JustificationRequiredException.class)
    ProblemDetail onJustificationRequired(JustificationRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Justification requise");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /* ══════════════ 401 ══════════════ */

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ProblemDetail onAuthFailure(Exception ex) {
        // Same message for every failure cause — no account enumeration. A
        // disabled account and a wrong password must be indistinguishable, or
        // the endpoint becomes a way to discover who holds an account.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication failed");
        pd.setDetail("Invalid email or password.");
        return pd;
    }

    /* ══════════════ 404 ══════════════ */

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail onNotFound(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not found");
        pd.setDetail("No resource at this path.");
        return pd;
    }

    @ExceptionHandler(SessionNotFoundException.class)
    ProblemDetail onSessionNotFound(SessionNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Session not found");
        pd.setDetail("No session with this identifier.");
        return pd;
    }

    @ExceptionHandler(ReviewerNotFoundException.class)
    ProblemDetail onReviewerNotFound(ReviewerNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Reviewer not found");
        pd.setDetail("No reviewer with this identifier.");
        return pd;
    }

    @ExceptionHandler(HonourCardNotFoundException.class)
    ProblemDetail onHonourCardNotFound(HonourCardNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Carte introuvable");
        // ⚠️ The identifier stays out of the response. The exception's own
        // message carries it for the log, where it is useful; an internal id
        // tells an administrator nothing.
        pd.setDetail("Aucune carte d'honneur ne correspond à cet identifiant.");
        return pd;
    }

    @ExceptionHandler(PrinterNotFoundException.class)
    ProblemDetail onPrinterNotFound(PrinterNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Imprimeur introuvable");
        pd.setDetail("Aucun compte d'impression ne correspond à cet identifiant.");
        return pd;
    }

    /**
     * 404, and deliberately so for someone else's dossier.
     *
     * A 403 would confirm the dossier exists — which is itself disclosure.
     * As far as a candidate is concerned, another candidate's file is not
     * forbidden; it does not exist.
     */
    @ExceptionHandler(ApplicationNotFoundException.class)
    ProblemDetail onApplicationNotFound(ApplicationNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Candidature introuvable");
        pd.setDetail("Aucune candidature ne correspond à cet identifiant.");
        return pd;
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail onDocumentNotFound(DocumentNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Document introuvable");
        pd.setDetail("Aucun document ne correspond à cet identifiant.");
        return pd;
    }

    /* ══════════════ 409 — the state says no ══════════════ */

    @ExceptionHandler(DuplicateEmailException.class)
    ProblemDetail onDuplicateEmail(DuplicateEmailException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Email already registered");
        pd.setDetail("An account already exists for this email address.");
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onIntegrityViolation(DataIntegrityViolationException ex) {
        // The DB constraint said no — a race the service check missed, or a
        // path that bypassed it. NEVER echo the constraint name: schema
        // details are internal, and a violation message names tables.
        log.warn("Integrity violation reached the handler", ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("The request conflicts with existing data.");
        return pd;
    }

    @ExceptionHandler(InvalidPhaseTransitionException.class)
    ProblemDetail onInvalidPhase(InvalidPhaseTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Invalid phase transition");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(SessionTooCloseException.class)
    ProblemDetail onSessionTooClose(SessionTooCloseException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Session trop rapprochée");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler({
            SessionNotOpenException.class,
            ApplicationAlreadySubmittedException.class,
            ApplicationNotEditableException.class,
            InvalidApplicationTransitionException.class
    })
    ProblemDetail onApplicationConflict(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Action impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * The review workflow's conflicts.
     *
     * All six carry French text written for the reviewer who will read it, so
     * the message passes through. CorrectionRequiredFirstException is the one
     * that encodes a LEGAL duty rather than a workflow rule — its message tells
     * the reviewer what to do instead, and that matters more than the status.
     */
    @ExceptionHandler({
            AlreadyClaimedException.class,
            NotYourClaimException.class,
            NotAwaitingReviewException.class,
            AlreadyDecidedException.class,
            CorrectionRoundExhaustedException.class,
            CorrectionRequiredFirstException.class
    })
    ProblemDetail onReviewConflict(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Action impossible");
        pd.setDetail(ex.getMessage());   // already French and reviewer-facing
        return pd;
    }

    @ExceptionHandler(NotCorrectableException.class)
    ProblemDetail onNotCorrectable(NotCorrectableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Correction impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * The objection right could not be exercised.
     *
     * NoEligibleReviewerException is the uncomfortable one: the candidate has
     * done nothing wrong and can do nothing about it — no second commission
     * member exists to re-examine their rejection. It is refused at filing
     * rather than left to stall, so an administrator learns of it while the
     * phase is still open.
     */
    @ExceptionHandler({
            ObjectionNotAllowedException.class,
            NoEligibleReviewerException.class
    })
    ProblemDetail onObjectionRefused(RuntimeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Réclamation impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(CardNotIssuableException.class)
    ProblemDetail onCardNotIssuable(CardNotIssuableException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Édition impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(CardLifecycleException.class)
    ProblemDetail onCardLifecycle(CardLifecycleException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Action impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * A print batch whose cards cannot share the requested layout.
     *
     * Currently one case: SHARED_BACK across cards with different expiry
     * dates. Refused rather than silently degraded — a common back would print
     * the wrong date on part of the run, and unlike a mis-ordered PDF that is
     * not visible until someone reads a card months later.
     */
    @ExceptionHandler(IncompatibleBatchException.class)
    ProblemDetail onIncompatibleBatch(IncompatibleBatchException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Impression impossible");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /* ══════════════ 413 ══════════════ */

    /** Spring rejects oversize uploads before our code runs. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail onUploadTooLarge(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
        pd.setTitle("Fichier trop volumineux");
        pd.setDetail("Le fichier dépasse la taille maximale autorisée (10 Mo).");
        return pd;
    }

    /* ══════════════ 422 — well-formed, but the state refuses ══════════════ */

    /**
     * 422 rather than 400: the request is well-formed and understood, but the
     * application's STATE does not permit submission.
     *
     * The response carries EVERY unmet condition, not the first — a candidate
     * told "your profile is incomplete", who fixes it and is then told "your
     * e-mail is unverified", has been sent away twice for one problem.
     */
    @ExceptionHandler(SubmissionRefusedException.class)
    ProblemDetail onSubmissionRefused(SubmissionRefusedException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Dossier non soumissible");
        pd.setDetail("Certaines conditions ne sont pas remplies.");
        pd.setProperty("blockers", ex.getBlockers().stream()
                .map(b -> Map.of(
                        "reason", b.reason().name(),
                        "message", b.messageFr()))
                .toList());
        return pd;
    }

    @ExceptionHandler(CorrectionIncompleteException.class)
    ProblemDetail onCorrectionIncomplete(CorrectionIncompleteException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Corrections incomplètes");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * A grant or an edit that cannot proceed: an expiry in the past, a missing
     * reason, a missing identity number.
     *
     * ⚠️ THE DETAIL IS A KEY, unlike most handlers in this file — the frontend
     * resolves it against the reader's catalogue. Do not wrap or prefix it:
     * "Erreur : validation.expiryMustBeFuture" would reach the screen exactly
     * like that.
     */
    @ExceptionHandler(HonourCardException.class)
    ProblemDetail onHonourCard(HonourCardException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Octroi impossible");
        pd.setDetail(ex.getMessage());   // a KEY — see above
        return pd;
    }

    /**
     * 422: the request is valid and authenticated, but the change it asks for
     * is not one that can happen — an account with no local password, or a
     * new password identical to the old.
     *
     * ⚠️ THE DETAIL IS A KEY, NOT A SENTENCE — unlike every other handler in
     * this file, which passes French text through. This one is resolved on
     * the client against the reader's catalogue, so it must not be wrapped or
     * prefixed: "Erreur : validation.passwordUnchanged" would reach the
     * screen exactly like that.
     */
    @ExceptionHandler(PasswordChangeException.class)
    ProblemDetail onPasswordChange(PasswordChangeException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setTitle("Modification impossible");
        pd.setDetail(ex.getMessage());   // a KEY — see above
        return pd;
    }

    /* ══════════════ 429 ══════════════ */

    @ExceptionHandler(TooManyRequestsException.class)
    ProblemDetail onTooManyRequests(TooManyRequestsException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        pd.setTitle("Trop de demandes");
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /* ══════════════ 500 — the catch-all ══════════════ */

    /**
     * Anything unhandled.
     *
     * The incident id is the whole point: it goes in the log beside the stack
     * trace and in the response, so a support request can be traced to an exact
     * failure WITHOUT the response having explained the system's internals to
     * whoever triggered it.
     *
     * Declared last for readability only — Spring dispatches on the most
     * specific type, not on declaration order.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        String incidentId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error [{}]", incidentId, ex);

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        pd.setDetail("An unexpected error occurred. Incident: " + incidentId);
        return pd;
    }

}
