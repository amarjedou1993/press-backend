//package com.presscard.press_accreditation.objection;
//
//import com.presscard.press_accreditation.user.UserRepository;
//import jakarta.validation.Valid;
//import jakarta.validation.constraints.NotNull;
//import jakarta.validation.constraints.Size;
//import org.springframework.security.access.prepost.PreAuthorize;
//import org.springframework.web.bind.annotation.*;
//
//import java.security.Principal;
//import java.util.List;
//
///**
// * The candidate's reclamation.
// *
// * The eligibility endpoint exists so the UI never has to decide whether an
// * objection is possible — the same principle as the submission gate. One
// * object says whether it may be filed, until when, and why not if not.
// */
//@RestController
//@RequestMapping("/api/applications/{id}/objection")
//@PreAuthorize("hasRole('CANDIDATE')")
//public class ObjectionController {
//
//    public record FileObjectionRequest(
//            @NotNull(message = "Sélectionnez un motif.") Long reasonId,
//            @Size(max = 4000) String argument
//    ) {}
//
//    public record ReasonOption(
//            Long id, String code, String labelFr, String labelAr,
//            String hintFr, boolean freeForm
//    ) {}
//
//    public record ObjectionResponse(
//            Long id, Long reasonId, String reasonLabelFr, String reasonLabelAr,
//            String argument, String createdAt
//    ) {}
//
//    private final ObjectionService objectionService;
//    private final UserRepository userRepository;
//
//    public ObjectionController(ObjectionService objectionService,
//                               UserRepository userRepository) {
//        this.objectionService = objectionService;
//        this.userRepository = userRepository;
//    }
//
//    /** May an objection be filed, until when, and why not if not. */
//    @GetMapping
//    public ObjectionService.ObjectionEligibility eligibility(@PathVariable Long id,
//                                                             Principal principal) {
//        return objectionService.eligibility(id, candidateId(principal));
//    }
//
//    /** The grounds HAPA allows, in HAPA's own order. */
//    @GetMapping("/reasons")
//    public List<ReasonOption> reasons() {
//        return objectionService.activeReasons().stream()
//                .map(r -> new ReasonOption(
//                        r.getId(), r.getCode(), r.getLabelFr(), r.getLabelAr(),
//                        r.getHintFr(), r.isFreeForm()))
//                .toList();
//    }
//
//    /** The objection already filed, if any. */
//    @GetMapping("/filed")
//    public ObjectionResponse filed(@PathVariable Long id, Principal principal) {
//        // Ownership is verified by the eligibility call's own lookup.
//        objectionService.eligibility(id, candidateId(principal));
//
//        Objection objection = objectionService.findByApplication(id);
//        if (objection == null) {
//            return null;
//        }
//        ObjectionReason reason = objectionService.reason(objection.getReasonId());
//
//        return new ObjectionResponse(
//                objection.getId(), objection.getReasonId(),
//                reason == null ? null : reason.getLabelFr(),
//                reason == null ? null : reason.getLabelAr(),
//                objection.getArgument(),
//                objection.getCreatedAt() == null ? null : objection.getCreatedAt().toString());
//    }
//
//    /** File it. */
//    @PostMapping
//    public ObjectionService.ObjectionEligibility file(
//            @PathVariable Long id,
//            @Valid @RequestBody FileObjectionRequest request,
//            Principal principal) {
//
//        Long candidate = candidateId(principal);
//        objectionService.file(id, candidate, request.reasonId(), request.argument());
//        // Answer with the new eligibility: alreadyFiled true, canObject false.
//        // The screen consumes that object anyway, so it re-renders correctly
//        // in the same round trip.
//        return objectionService.eligibility(id, candidate);
//    }
//
//    private Long candidateId(Principal principal) {
//        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
//    }
//}

package com.presscard.press_accreditation.objection;

import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * The candidate's reclamation.
 *
 * The eligibility endpoint exists so the UI never has to decide whether an
 * objection is possible — the same principle as the submission gate. One
 * object says whether it may be filed, until when, and why not if not.
 *
 * ⚠️ EVERY LABEL TRAVELS IN BOTH LANGUAGES. This is the screen where a
 * candidate contests a refusal; a French hint under an Arabic ground would
 * be the mixed-language failure at the worst possible moment.
 */
@RestController
@RequestMapping("/api/applications/{id}/objection")
@PreAuthorize("hasRole('CANDIDATE')")
public class ObjectionController {

    public record FileObjectionRequest(
            // A KEY, resolved by the reader's own catalogue.
            @NotNull(message = "validation.groundRequired") Long reasonId,
            @Size(max = 4000) String argument
    ) {}

    public record ReasonOption(
            Long id, String code,
            String labelFr, String labelAr,
            String hintFr, String hintAr,     // ← hintAr added
            boolean freeForm
    ) {}

    public record ObjectionResponse(
            Long id, Long reasonId, String reasonLabelFr, String reasonLabelAr,
            String argument, String createdAt
    ) {}

    private final ObjectionService objectionService;
    private final UserRepository userRepository;

    public ObjectionController(ObjectionService objectionService,
                               UserRepository userRepository) {
        this.objectionService = objectionService;
        this.userRepository = userRepository;
    }

    /** May an objection be filed, until when, and why not if not. */
    @GetMapping
    public ObjectionService.ObjectionEligibility eligibility(@PathVariable Long id,
                                                             Principal principal) {
        return objectionService.eligibility(id, candidateId(principal));
    }

    /** The grounds the Ministry allows, in the Ministry's own order. */
    @GetMapping("/reasons")
    public List<ReasonOption> reasons() {
        return objectionService.activeReasons().stream()
                .map(r -> new ReasonOption(
                        r.getId(), r.getCode(),
                        r.getLabelFr(), r.getLabelAr(),
                        r.getHintFr(), r.getHintAr(),
                        r.isFreeForm()))
                .toList();
    }

    /**
     * The objection already filed, if any.
     *
     * 204 rather than a null body: a null from a @RestController is a 200
     * with nothing in it, which a client has to special-case. "No objection
     * exists" is an absence, and 204 says so in the status line.
     */
    @GetMapping("/filed")
    public ResponseEntity<ObjectionResponse> filed(@PathVariable Long id,
                                                   Principal principal) {
        // Ownership is verified by the eligibility call's own lookup.
        objectionService.eligibility(id, candidateId(principal));

        Objection objection = objectionService.findByApplication(id);
        if (objection == null) {
            return ResponseEntity.noContent().build();
        }
        ObjectionReason reason = objectionService.reason(objection.getReasonId());

        return ResponseEntity.ok(new ObjectionResponse(
                objection.getId(), objection.getReasonId(),
                reason == null ? null : reason.getLabelFr(),
                reason == null ? null : reason.getLabelAr(),
                objection.getArgument(),
                objection.getCreatedAt() == null ? null : objection.getCreatedAt().toString()));
    }

    /** File it. */
    @PostMapping
    public ObjectionService.ObjectionEligibility file(
            @PathVariable Long id,
            @Valid @RequestBody FileObjectionRequest request,
            Principal principal) {

        Long candidate = candidateId(principal);
        objectionService.file(id, candidate, request.reasonId(), request.argument());
        // Answer with the new eligibility: alreadyFiled true, canObject false.
        // The screen consumes that object anyway, so it re-renders correctly
        // in the same round trip.
        return objectionService.eligibility(id, candidate);
    }

    private Long candidateId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
