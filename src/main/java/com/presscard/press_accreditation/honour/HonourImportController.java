package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Bulk honour card grants, from a spreadsheet.
 *
 * SUPER_ADMIN-gated by SecurityConfig (/api/admin/**).
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ TWO STEPS, AND THE SEPARATION IS THE POINT.
 *
 * /preview reads and judges. It takes no card number, writes no row, and can
 * be run as many times as an administrator likes while they fix their file.
 *
 * /commit grants. By then the report has been read and the set confirmed.
 *
 * A single endpoint doing both would show the administrator what HAS happened
 * rather than what WOULD — and forty cards granted from a file with a wrong
 * column is not undone by an apology.
 * ───────────────────────────────────────────────────────────────────────
 */
@RestController
@RequestMapping("/api/admin/honour-cards/import")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class HonourImportController {

    private final HonourImportService importService;
    private final HonourImportCommitter committer;
    private final HonourImportTemplate template;
    private final UserRepository userRepository;

    public HonourImportController(HonourImportService importService,
                                  HonourImportCommitter committer,
                                  HonourImportTemplate template,
                                  UserRepository userRepository) {
        this.importService = importService;
        this.committer = committer;
        this.template = template;
        this.userRepository = userRepository;
    }

    /**
     * The blank workbook, with the right columns and the reference lists.
     *
     * ⚠️ WITHOUT THIS THE FIRST IMPORT FAILS on column order, and the
     * administrator has no way to learn what the columns should be. A format
     * nobody can produce is a feature nobody can use.
     */
    @GetMapping(value = "/template", produces =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"modele-cartes-honneur-%s.xlsx\""
                                .formatted(LocalDate.now()))
                .body(template.build());
    }

    /**
     * Read the archive and report what would happen.
     *
     * ⚠️ NOTHING IS WRITTEN. Every row is checked — identity, checksum,
     * duplicates against the register and within the file, dates, the grant
     * reason — and a photograph is looked for by identity number.
     *
     * ⚠️ AND THE PHOTOGRAPHS ARE DISCARDED when this returns. The commit
     * takes the file again; see HonourImportCommitter for why holding ten
     * megabytes between two requests was the worse trade.
     */
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HonourImportService.ImportPreview preview(
            @RequestParam("file") MultipartFile file) {
        return importService.parse(file).preview();
    }

    /** What the administrator confirmed, and the file that proves it. */
    public record CommitBody(
            @NotEmpty(message = "La confirmation ne porte sur aucune ligne.")
            List<String> identityNumbers
    ) {}

    /**
     * Grant the confirmed rows.
     *
     * ⚠️ THE ARCHIVE COMES AGAIN, and identityNumbers with it.
     *
     * The committer re-parses and refuses if the file no longer describes the
     * set that was confirmed — a file edited between the two steps, a
     * different one selected, or a person granted a card by a colleague in
     * the meantime.
     *
     * Multipart rather than JSON because the file travels alongside: the ids
     * arrive as a repeated form field.
     */
    @PostMapping(value = "/commit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HonourImportCommitter.CommitResult commit(
            @RequestParam("file") MultipartFile file,
            @RequestParam("identityNumbers") List<String> identityNumbers,
            Principal principal) {

        if (identityNumbers == null || identityNumbers.isEmpty()) {
            throw new HonourImportException(
                    "La confirmation ne porte sur aucune ligne. Reprenez l'aperçu.");
        }
        return committer.commit(file, identityNumbers, actorId(principal));
    }

    private Long actorId(Principal principal) {
        return userRepository.findByEmail(principal.getName()).orElseThrow().getId();
    }
}
