package com.presscard.press_accreditation.profile;

import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import com.presscard.press_accreditation.validation.ValidNni;
import com.presscard.press_accreditation.validation.ValidPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;

/**
 * The candidate's own identity record. Kept on the PERSON rather than on each
 * application, so a journalist applying again next year re-types nothing.
 *
 * The NNI is unique across candidates: two people cannot claim the same
 * national identity number, which the database enforces and this controller
 * reports as a clean 409.
 */
@RestController
@RequestMapping("/api/me")
@PreAuthorize("hasRole('CANDIDATE')")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger("PROFILE_AUDIT");

    /* ── contracts ── */

    public record ProfileRequest(
            @ValidNni @Size(max = 20) String nni,
            @Size(max = 30) String passportNo,
            @NotNull @Past(message = "La date de naissance doit être dans le passé.")
            LocalDate birthdate,
            @NotBlank @Size(max = 200) String birthplace
    ) {
        /** V1.3 §D: national ID or passport — at least one. */
        public boolean hasIdentityDocument() {
            return (nni != null && !nni.isBlank())
                    || (passportNo != null && !passportNo.isBlank());
        }
    }

    public record AccountRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @ValidPhone @Size(max = 30) String phone
    ) {}

    public record MeResponse(
            Long id,
            String email,
            String fullName,
            String phone,
            String role,
            boolean emailVerified,
            ProfileResponse profile,
            boolean profileComplete
    ) {}

    public record ProfileResponse(
            String nni,
            String passportNo,
            LocalDate birthdate,
            String birthplace
    ) {
        static ProfileResponse of(CandidateProfile p) {
            return new ProfileResponse(p.getNni(), p.getPassportNo(),
                    p.getBirthdate(), p.getBirthplace());
        }
    }

    private final UserRepository userRepository;
    private final CandidateProfileRepository profileRepository;

    public ProfileController(UserRepository userRepository,
                             CandidateProfileRepository profileRepository) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
    }

    /* ── read ── */

    @GetMapping
    @Transactional(readOnly = true)
    public MeResponse me(Principal principal) {
        User user = currentUser(principal);
        CandidateProfile profile = profileRepository.findById(user.getId()).orElse(null);

        return new MeResponse(
                user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                user.getRole().name(), user.isEmailVerified(),
                profile == null ? null : ProfileResponse.of(profile),
                profile != null && profile.isComplete());
    }

    /* ── write ── */

    /** Name and phone. E-mail changes go through the verification flow. */
    @PutMapping("/account")
    @Transactional
    public MeResponse updateAccount(@Valid @RequestBody AccountRequest request,
                                    Principal principal) {
        User user = currentUser(principal);
        user.setFullName(request.fullName().trim());
        user.setPhone(request.phone().replaceAll("\\s", ""));
        userRepository.save(user);

        log.info("ACCOUNT_UPDATED user={}", user.getEmail());
        return me(principal);
    }

    /** Create or replace the identity record. */
    @PutMapping("/profile")
    @Transactional
    public MeResponse updateProfile(@Valid @RequestBody ProfileRequest request,
                                    Principal principal) {
        User user = currentUser(principal);

        if (!request.hasIdentityDocument()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Renseignez votre NNI ou votre numéro de passeport.");
        }

        // The NNI identifies a person: refuse one already claimed by someone else.
        if (request.nni() != null && !request.nni().isBlank()) {
            profileRepository.findByNni(request.nni()).ifPresent(existing -> {
                if (!existing.getUserId().equals(user.getId())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ce NNI est déjà associé à un autre compte.");
                }
            });
        }

        CandidateProfile profile = profileRepository.findById(user.getId())
                .orElseGet(() -> CandidateProfile.builder().userId(user.getId()).build());

        profile.setNni(blankToNull(request.nni()));
        profile.setPassportNo(blankToNull(request.passportNo()));
        profile.setBirthdate(request.birthdate());
        profile.setBirthplace(request.birthplace().trim());
        profileRepository.save(profile);

        log.info("PROFILE_UPDATED user={} complete={}", user.getEmail(), profile.isComplete());
        return me(principal);
    }

    /* ── helpers ── */

    private User currentUser(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow();
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
