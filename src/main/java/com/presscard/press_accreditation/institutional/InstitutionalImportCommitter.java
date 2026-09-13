package com.presscard.press_accreditation.institutional;

import com.presscard.press_accreditation.storage.PhotoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turning a validated archive into filings.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS CREATES FILINGS, NOT CARDS — AND THAT CHANGES EVERYTHING.
 *
 * Its twin, HonourImportCommitter, grants cards: it takes a B number per row
 * from a sequence that must run unbroken, which is why it goes to such
 * lengths to avoid leaving gaps.
 *
 * Here nothing is granted. The institution files its staff; the Ministry
 * grants afterwards, and the C number is taken THEN. So a failed row costs
 * nothing but itself — no sequence value is spent, no hole appears in the
 * register.
 *
 * ⚠️ WHICH MEANS THE ARCHIVE NEED NOT BE RE-UPLOADED.
 *
 * The honour importer asks for the file twice, because confirming a grant of
 * forty cards deserves a check that the file still describes the same forty.
 * A filing is reversible — withdrawFiling deletes it — so the stakes do not
 * justify making an institution upload ten megabytes a second time.
 *
 * One upload, preview and commit in one call. The difference is not
 * carelessness; it is that the two acts are not equally hard to undo.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class InstitutionalImportCommitter {

    private static final Logger log = LoggerFactory.getLogger("INSTITUTIONAL_IMPORT");

    private final InstitutionalImportService importService;
    private final InstitutionalCardService cardService;
    private final InstitutionalCardRepository repository;
    private final PhotoStorageService photoStorage;

    public InstitutionalImportCommitter(InstitutionalImportService importService,
                                        InstitutionalCardService cardService,
                                        InstitutionalCardRepository repository,
                                        PhotoStorageService photoStorage) {
        this.importService = importService;
        this.cardService = cardService;
        this.repository = repository;
        this.photoStorage = photoStorage;
    }

    /** What became of one row. */
    public record RowOutcome(
            int rowNumber,
            String fullName,
            boolean filed,
            Long cardId,
            boolean photoAttached,
            String failureFr
    ) {}

    public record CommitResult(
            int requested,
            int filed,
            int failed,
            int photosAttached,
            List<RowOutcome> outcomes,
            /** Rows the parser refused — reported, never silently dropped. */
            List<InstitutionalImportService.ImportRow> rejected
    ) {}

    /**
     * Read the archive and file every valid row.
     *
     * @param institutionId taken from the SIGNED-IN ACCOUNT, never from the
     *                      request body — an institution must not be able to
     *                      file staff for another.
     */
    @Transactional
    public CommitResult commit(Long institutionId, MultipartFile archive, Long filedBy) {
        InstitutionalImportService.ParsedArchive parsed =
                importService.parse(institutionId, archive);

        List<InstitutionalImportService.ImportRow> valid = parsed.preview().rows().stream()
                .filter(InstitutionalImportService.ImportRow::valid)
                .toList();

        List<InstitutionalImportService.ImportRow> rejected = parsed.preview().rows().stream()
                .filter(r -> !r.valid())
                .toList();

        List<RowOutcome> outcomes = new ArrayList<>();
        int filed = 0;
        int failed = 0;
        int photos = 0;

        for (InstitutionalImportService.ImportRow row : valid) {
            /*
             * ⚠️ ONE ROW'S FAILURE COSTS ONLY THAT ROW.
             *
             * The same rule as CardService.issueMany and the honour importer:
             * one employee filed twice must not cost the other thirty-nine
             * their filings — and the institution reads WHO failed rather
             * than a count.
             */
            try {
                InstitutionalCard card = cardService.file(institutionId,
                        new InstitutionalCardService.FilingRequest(
                                row.fullName(),
                                row.identityNumber(),
                                row.birthdate(),
                                row.birthplace(),
                                row.jobTitle(),
                                row.categoryId(),
                                row.specialisationId()),
                        filedBy);

                boolean attached = attachPhoto(card, row, parsed.photos());
                if (attached) photos++;

                outcomes.add(new RowOutcome(row.rowNumber(), row.fullName(), true,
                        card.getId(), attached, null));
                filed++;

            } catch (RuntimeException e) {
                outcomes.add(new RowOutcome(row.rowNumber(), row.fullName(), false,
                        null, false, e.getMessage()));
                failed++;
                log.warn("INSTITUTIONAL_IMPORT_ROW_FAILED institution={} row={} name={} reason={}",
                        institutionId, row.rowNumber(), row.fullName(), e.getMessage());
            }
        }

        log.info("INSTITUTIONAL_IMPORT_COMMITTED institution={} by={} rows={} filed={} "
                        + "failed={} rejected={} photos={}",
                institutionId, filedBy, valid.size(), filed, failed, rejected.size(), photos);

        return new CommitResult(valid.size(), filed, failed, photos, outcomes, rejected);
    }

    /* ══ internals ══ */

    /**
     * Attach the photograph, if the archive carried one.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ A FAILURE HERE DOES NOT UNDO THE FILING.
     *
     * The person is filed; what failed is one JPEG. Rolling back would force
     * the institution to re-import the whole employee because an image was
     * 500 by 400 — and the filing is exactly the thing that is easy to keep.
     *
     * The card then waits: findProducible refuses a row without a photograph,
     * so nothing faceless reaches the printer. The outcome says so, and the
     * institution's screen shows "Photo requise" on the line.
     * ───────────────────────────────────────────────────────────────────
     */
    private boolean attachPhoto(InstitutionalCard card,
                                InstitutionalImportService.ImportRow row,
                                Map<String, InstitutionalImportService.PhotoFile> photos) {
        if (!row.hasPhoto()) {
            return false;
        }
        InstitutionalImportService.PhotoFile file = photos.get(row.identityNumber());
        if (file == null) {
            return false;
        }

        try {
            /*
             * ⚠️ THROUGH PhotoStorageService, so an archive's photographs meet
             * exactly the rules a single upload meets: JPEG or PNG, 600x800
             * minimum, portrait. A second validation path would eventually
             * disagree with the first, and a card printed from an undersized
             * photograph is unusable at a checkpoint.
             */
            // ⚠️ null as the previous path: an import only ever creates
            // filings, so there is never a photograph to supersede.
            String path = photoStorage.storeForInstitutionalCard(
                    new InMemoryMultipartFile(file.filename(), file.content()),
                    card.getId(),
                    null);

            card.setPhotoPath(path);
            card.setPhotoUploadedAt(java.time.OffsetDateTime.now());
            repository.save(card);
            return true;

        } catch (RuntimeException e) {
            log.warn("INSTITUTIONAL_IMPORT_PHOTO_FAILED card={} file={} reason={}",
                    card.getId(), file.filename(), e.getMessage());
            return false;
        }
    }

    /**
     * A MultipartFile over bytes already in hand.
     *
     * ⚠️ Exists so the import can use PhotoStorageService unchanged — the
     * same reason its twin in HonourImportCommitter does. A byte[] variant
     * would mean two implementations of "is this photograph usable", and they
     * would drift.
     */
    private record InMemoryMultipartFile(String filename, byte[] content)
            implements MultipartFile {

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }

        @Override public String getContentType() {
            return filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        }

        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
        @Override public byte[] getBytes() { return content; }
        @Override public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override public void transferTo(java.io.File destination) throws IOException {
            java.nio.file.Files.write(destination.toPath(), content);
        }
    }
}