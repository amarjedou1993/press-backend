package com.presscard.press_accreditation.admin;

import com.presscard.press_accreditation.admin.PrinterService.DeleteOutcome;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.validation.ValidPassword;
import com.presscard.press_accreditation.validation.ValidPhone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Producer account management.
 *
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**). Thin controller —
 * PrinterService owns the rules, including the two-tier delete.
 *
 * ⚠️ SEPARATE FROM AdminReviewerController, and separate on purpose.
 *
 * A reviewer is a member of a commission: their number matters, their absence
 * breaks the objection right, and the screen that manages them says so. A
 * producer is a contractor with an account. Forcing both through one endpoint
 * would mean one screen making an argument that is true of only half its
 * subjects.
 */
@RestController
@RequestMapping("/api/admin/printers")
public class AdminPrinterController {

    /* ── request/response contracts ── */

    public record CreatePrinterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @ValidPhone @Size(max = 30) String phone,          // optional for staff
            @NotBlank @ValidPassword @Size(max = 100) String password
    ) {}

    public record UpdatePrinterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotBlank @Email @Size(max = 255) String email,
            @ValidPhone @Size(max = 30) String phone
    ) {}

    public record SetEnabledRequest(boolean enabled) {}

    public record PrinterResponse(
            Long id, String email, String fullName, String phone,
            String role, boolean enabled
    ) {
        static PrinterResponse of(User u) {
            return new PrinterResponse(u.getId(), u.getEmail(), u.getFullName(),
                    u.getPhone(), u.getRole().name(), u.isEnabled());
        }
    }

    /** Delete result tells the admin what actually happened. */
    public record DeleteResult(String outcome, String message) {}

    private final PrinterService printerService;

    public AdminPrinterController(PrinterService printerService) {
        this.printerService = printerService;
    }

    /* ── endpoints ── */

    @GetMapping
    public List<PrinterResponse> list() {
        return printerService.listPrinters().stream()
                .map(PrinterResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<PrinterResponse> create(@Valid @RequestBody CreatePrinterRequest req,
                                                  Principal principal) {
        User created = printerService.create(
                req.fullName(), req.email(), req.phone(), req.password(), actor(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(PrinterResponse.of(created));
    }

    @PutMapping("/{id}")
    public PrinterResponse update(@PathVariable Long id,
                                  @Valid @RequestBody UpdatePrinterRequest req,
                                  Principal principal) {
        return PrinterResponse.of(printerService.update(
                id, req.fullName(), req.email(), req.phone(), actor(principal)));
    }

    @PatchMapping("/{id}/enabled")
    public PrinterResponse setEnabled(@PathVariable Long id,
                                      @RequestBody SetEnabledRequest req,
                                      Principal principal) {
        return PrinterResponse.of(
                printerService.setEnabled(id, req.enabled(), actor(principal)));
    }

    @DeleteMapping("/{id}")
    public DeleteResult delete(@PathVariable Long id, Principal principal) {
        DeleteOutcome outcome = printerService.delete(id, actor(principal));
        String message = outcome == DeleteOutcome.DELETED
                ? "Compte supprimé."
                : "Compte désactivé — un historique de production empêche la suppression définitive.";
        return new DeleteResult(outcome.name(), message);
    }

    private String actor(Principal principal) {
        return principal != null ? principal.getName() : "unknown";
    }
}
