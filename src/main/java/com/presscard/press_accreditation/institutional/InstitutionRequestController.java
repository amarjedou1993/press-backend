package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.validation.ValidPassword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * The applicant's side: submit, then confirm the address.
 *
 * ⚠️ UNDER /api/auth, WHICH SecurityConfig ALREADY OPENS.
 *
 * An applicant has no account — that is the point — so these endpoints must
 * be reachable without a session, exactly like candidate registration beside
 * them. No change to the security rules was needed, and none should be made
 * to "tidy" this under /api/public: the review endpoints stay under
 * /api/admin, behind the Ministry's role.
 */
@RestController
@RequestMapping("/api/auth/institution-requests")
public class InstitutionRequestController {

    private final InstitutionRequestService service;

    public InstitutionRequestController(InstitutionRequestService service) {
        this.service = service;
    }

    /**
     * The applicant's declaration.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ EVERY MESSAGE IS A KEY THAT EXISTS IN THE CATALOGUES.
     *
     * The first version used "validation.required" — a key no catalogue
     * defined, so the form displayed it raw, beside the field, in both
     * languages. Each field now names its own key, shared with the client's
     * validateInstitutionRequest and, where the rule is the same, with the
     * candidate's registration.
     *
     * ⚠️ THE PHONE IS REQUIRED AND USES THE CANDIDATE'S PATTERN.
     *
     * It was optional with only a length limit, so anything passed. The
     * Ministry may need to call the signatory to confirm the letter — the
     * check that defeats a request filed in a body's name by someone outside
     * it — and a number that cannot be dialled makes that impossible.
     * ───────────────────────────────────────────────────────────────────
     */
    public record SubmitForm(
            @NotBlank(message = "validation.requiredInstitutionNameFr")
            @Size(max = 200) String proposedNameFr,

            @NotBlank(message = "validation.requiredInstitutionNameAr")
            @Size(max = 200) String proposedNameAr,

            @NotBlank(message = "validation.requiredName")
            @Size(max = 200) String contactName,

            @NotBlank(message = "validation.requiredContactRole")
            @Size(max = 200) String contactRole,

            @NotBlank(message = "validation.requiredEmail")
            @Email(message = "validation.email") String email,

            @NotBlank(message = "validation.requiredPhone")
            @Pattern(regexp = "^(\\+222)?[234]\\d{7}$", message = "validation.phone")
            String phone,

            @NotBlank(message = "validation.requiredPassword")
            @ValidPassword String password,

            String locale
    ) {}

    public record TokenBody(@NotBlank String token) {}

    /** What the applicant sees afterwards — and nothing that identifies the request. */
    public record SubmittedResponse(String email) {}

    public record ConfirmedResponse(boolean alreadyConfirmed) {}

    /**
     * ⚠️ MULTIPART: the fields as a form, the letter as a file part. JSON
     * cannot carry the letter, and a second request to upload it would leave
     * a window where a request exists without its evidence.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SubmittedResponse submit(@Valid @ModelAttribute SubmitForm form,
                                    @RequestPart("letter") MultipartFile letter) {
        InstitutionRequest request = service.submit(
                new InstitutionRequestService.SubmitRequest(
                        form.proposedNameFr(), form.proposedNameAr(),
                        form.contactName(), form.contactRole(),
                        form.email(), form.phone(), form.password(), form.locale()),
                letter);
        return new SubmittedResponse(request.getEmail());
    }

    @PostMapping("/confirm")
    public ConfirmedResponse confirm(@Valid @RequestBody TokenBody body) {
        return new ConfirmedResponse(!service.confirm(body.token()));
    }
}