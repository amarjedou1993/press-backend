package com.presscard.press_accreditation.error;

import com.presscard.press_accreditation.session.SessionNotFoundException;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One place where exceptions become HTTP answers (RFC 7807 ProblemDetail).
 *
 * Hardened vs week 1: malformed JSON, unknown routes, type mismatches, DB
 * constraint violations, and unexpected exceptions all produce clean,
 * uninformative-by-design responses. A 500 tells the client NOTHING except
 * an incident id; the real stack trace is logged server-side under that id.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /* ── 400s ─────────────────────────────────────────────────── */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setTitle("Validation failed");
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

    /* ── 401 ──────────────────────────────────────────────────── */

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ProblemDetail onAuthFailure(Exception ex) {
        // Same message for every failure cause — no account enumeration.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        pd.setTitle("Authentication failed");
        pd.setDetail("Invalid email or password.");
        return pd;
    }

    /* ── 404 ──────────────────────────────────────────────────── */

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail onNotFound(NoResourceFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not found");
        pd.setDetail("No resource at this path.");
        return pd;
    }

    /* ── 409 ──────────────────────────────────────────────────── */

    @ExceptionHandler(DuplicateEmailException.class)
    ProblemDetail onDuplicateEmail(DuplicateEmailException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Email already registered");
        pd.setDetail("An account already exists for this email address.");
        return pd;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail onIntegrityViolation(DataIntegrityViolationException ex) {
        // The DB constraint said no (race the service check missed, etc.).
        // Never echo the constraint name — schema details are internal.
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Conflict");
        pd.setDetail("The request conflicts with existing data.");
        return pd;
    }

    /* ── 500 — the catch-all ──────────────────────────────────── */

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        String incidentId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unexpected error [{}]", incidentId, ex);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setTitle("Internal error");
        pd.setDetail("An unexpected error occurred. Incident: " + incidentId);
        return pd;
    }

    @ExceptionHandler(SessionNotFoundException.class)
    ProblemDetail onSessionNotFound(SessionNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Session not found");
        pd.setDetail("No session with this identifier.");
        return pd;
    }

    @ExceptionHandler(InvalidPhaseTransitionException.class)
    ProblemDetail onInvalidPhase(InvalidPhaseTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setTitle("Invalid phase transition");
        pd.setDetail(ex.getMessage());
        return pd;
    }
}
