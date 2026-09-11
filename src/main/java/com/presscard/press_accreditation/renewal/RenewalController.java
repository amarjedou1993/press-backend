package com.presscard.press_accreditation.renewal;

import com.presscard.press_accreditation.application.Application;
import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.user.UserRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * The renewal, as the holder sees it.
 *
 * ⚠️ ONE ENDPOINT. Everything after "yes, renew me" is the ordinary
 * candidature path — the same dossier, the same commission, the same card.
 * This only answers "may I, and on what card", and supplies what the previous
 * dossier said so the holder can confirm it rather than retype it.
 */
@RestController
@RequestMapping("/api/renewal")
@PreAuthorize("hasRole('CANDIDATE')")
public class RenewalController {

    /** Eligibility, plus what the last dossier said. */
    public record RenewalContext(
            RenewalService.RenewalEligibility eligibility,
            /**
             * The outlet and specialisation of the previous candidature.
             *
             * ⚠️ SUPPLIED SO THE HOLDER CAN CONFIRM THEM, not so a form can
             * arrive filled in. The screen asks "do you still work for X?"
             * with two buttons — a pre-filled text box invites scrolling
             * past, and whether the employer has changed is the one fact a
             * renewal exists to establish.
             *
             * Null when no previous dossier can be found, in which case the
             * screen asks for both from scratch.
             */
            Long previousSpecialisationId,
            String previousSpecialisationFr,
            String previousInstitution
    ) {}

    private final RenewalService renewalService;
    private final ApplicationRepository applicationRepository;
    private final SpecialisationRepository specialisationRepository;
    private final UserRepository userRepository;

    public RenewalController(RenewalService renewalService,
                             ApplicationRepository applicationRepository,
                             SpecialisationRepository specialisationRepository,
                             UserRepository userRepository) {
        this.renewalService = renewalService;
        this.applicationRepository = applicationRepository;
        this.specialisationRepository = specialisationRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/context")
    @Transactional(readOnly = true)
    public RenewalContext context(Principal principal) {
        Long candidateId = candidateId(principal);

        RenewalService.RenewalEligibility eligibility =
                renewalService.eligibilityFor(candidateId);

        Application previous = applicationRepository
                .findLastIssuedFor(candidateId).orElse(null);

        Specialisation specialisation = previous == null
                || previous.getSpecialisationId() == null
                ? null
                : specialisationRepository.findById(previous.getSpecialisationId())
                .orElse(null);

        return new RenewalContext(
                eligibility,
                specialisation == null ? null : specialisation.getId(),
                specialisation == null ? null : specialisation.getLabelFr(),
                previous == null ? null : previous.getInstitution());
    }

    private Long candidateId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}