package com.presscard.press_accreditation.honour;

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
 * Turning a validated import into cards.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE ARCHIVE IS UPLOADED TWICE, AND THAT IS THE DESIGN.
 *
 * The preview holds ten megabytes of photographs in memory. Carrying that
 * between two requests means either an HTTP session — invisible state that
 * expires while an administrator reads a forty-row report — or a cache, which
 * is the same problem with a timer.
 *
 * So the commit takes the file again and re-parses it. The second parse is
 * not waste: it is the CHECK. The administrator confirmed a specific set of
 * people, and this verifies the file still describes that set before a single
 * card number is taken.
 *
 * Stateless, survives a restart, and cannot commit something other than what
 * was shown. The cost is a second upload — seconds, twice a year.
 * ───────────────────────────────────────────────────────────────────────
 *
 * ⚠️ AND EACH CARD GOES THROUGH THE ORDINARY GRANT PATH.
 *
 * HonourCardService.grant validates, takes one number, signs. A bulk
 * shortcut writing rows directly would be a second implementation of the
 * signature — and the day the two disagree, a batch of cards scans as
 * unverifiable.
 */
@Service
public class HonourImportCommitter {

    private static final Logger log = LoggerFactory.getLogger("HONOUR_IMPORT");

    private final HonourImportService importService;
    private final HonourCardService cardService;
    private final PhotoStorageService photoStorage;
    private final HonourCardRepository repository;

    public HonourImportCommitter(HonourImportService importService,
                                 HonourCardService cardService,
                                 PhotoStorageService photoStorage,
                                 HonourCardRepository repository) {
        this.importService = importService;
        this.cardService = cardService;
        this.photoStorage = photoStorage;
        this.repository = repository;
    }

    /** What became of one row. */
    public record RowOutcome(
            int rowNumber,
            String fullName,
            boolean granted,
            String cardNumber,
            boolean photoAttached,
            String failureFr
    ) {}

    public record CommitResult(
            int requested,
            int granted,
            int failed,
            int photosAttached,
            List<RowOutcome> outcomes
    ) {}

    /**
     * Grant every valid row.
     *
     * @param expectedIdentities the identity numbers the administrator saw in
     *        the preview and confirmed. The commit refuses if the file no
     *        longer describes them.
     */
    public CommitResult commit(MultipartFile archive,
                               List<String> expectedIdentities,
                               Long actorId) {

        HonourImportService.ParsedArchive parsed = importService.parse(archive);
        List<HonourImportService.ImportRow> valid = parsed.preview().rows().stream()
                .filter(HonourImportService.ImportRow::valid)
                .toList();

        verifyMatchesPreview(valid, expectedIdentities);

        List<RowOutcome> outcomes = new ArrayList<>();
        int granted = 0;
        int failed = 0;
        int photos = 0;

        for (HonourImportService.ImportRow row : valid) {
            /*
             * ⚠️ ONE TRANSACTION PER ROW, not one for the batch.
             *
             * The same rule as CardService.issueMany: one candidate with an
             * unreadable photograph must not cost the other thirty-nine their
             * cards. And a rolled-back batch would still have consumed every
             * sequence value it touched — the gaps would remain while the
             * cards did not.
             */
            try {
                HonourCard card = grantOne(row, actorId);
                boolean attached = attachPhoto(card, row, parsed.photos());
                if (attached) photos++;

                outcomes.add(new RowOutcome(row.rowNumber(), row.fullName(), true,
                        card.getCardNumber(), attached, null));
                granted++;

            } catch (RuntimeException e) {
                // Named, never swallowed: the administrator must know WHICH
                // row failed and why, or the import is a black box.
                outcomes.add(new RowOutcome(row.rowNumber(), row.fullName(), false,
                        null, false, e.getMessage()));
                failed++;
                log.warn("HONOUR_IMPORT_ROW_FAILED row={} name={} reason={}",
                        row.rowNumber(), row.fullName(), e.getMessage());
            }
        }

        log.info("HONOUR_IMPORT_COMMITTED actor={} requested={} granted={} failed={} photos={}",
                actorId, valid.size(), granted, failed, photos);

        return new CommitResult(valid.size(), granted, failed, photos, outcomes);
    }

    /* ══ internals ══ */

    /**
     * ⚠️ THE SECOND PARSE IS THE CHECK, and this is where it pays.
     *
     * Between the preview and the confirmation the file may have been edited,
     * or a different one selected, or another administrator may have granted
     * one of these people a card in the meantime — which the re-parse catches,
     * because the row would now carry a duplicate-identity error and fall out
     * of the valid set.
     *
     * Refusing the whole import rather than granting the intersection: an
     * administrator who approved forty people and would silently get
     * thirty-eight has not approved thirty-eight.
     */
    private void verifyMatchesPreview(List<HonourImportService.ImportRow> valid,
                                      List<String> expectedIdentities) {
        if (expectedIdentities == null || expectedIdentities.isEmpty()) {
            throw new HonourImportException(
                    "La confirmation ne porte sur aucune ligne. Reprenez l'aperçu.");
        }

        List<String> actual = valid.stream()
                .map(HonourImportService.ImportRow::identityNumber)
                .sorted()
                .toList();
        List<String> expected = expectedIdentities.stream().sorted().toList();

        if (!actual.equals(expected)) {
            throw new HonourImportException(
                    ("Le fichier ne correspond plus à l'aperçu confirmé "
                   + "(%d ligne(s) valides attendues, %d trouvées). "
                   + "Relancez l'aperçu et vérifiez avant de valider.")
                            .formatted(expected.size(), actual.size()));
        }
    }

    /**
     * One card, through the ordinary path.
     *
     * ⚠️ Its own transaction, so a failure here rolls back this row and
     * nothing else.
     */
    @Transactional
    protected HonourCard grantOne(HonourImportService.ImportRow row, Long actorId) {
        return cardService.grant(new HonourCardService.GrantRequest(
                row.fullName(),
                row.identityNumber(),
                row.birthdate(),
                row.birthplace(),
                row.categoryId(),
                row.specialisationId(),
                row.institution(),
                row.expiresAt(),
                row.grantReason()), actorId);
    }

    /**
     * Attach the photograph, if the archive carried one.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ A FAILURE HERE DOES NOT UNDO THE GRANT.
     *
     * The card number has been taken and the row is signed. Rolling back for
     * an unreadable photograph would leave a gap in the sequence and force
     * the whole person to be re-imported — when what actually happened is
     * that one JPEG was too small.
     *
     * So the card stands, without a photograph, and findProducible keeps it
     * out of the printer's queue until someone attaches one. The outcome says
     * so, and the administration screen shows "Photo requise" on the row.
     * ───────────────────────────────────────────────────────────────────
     */
    private boolean attachPhoto(HonourCard card,
                                HonourImportService.ImportRow row,
                                Map<String, HonourImportService.PhotoFile> photos) {
        if (!row.hasPhoto()) {
            return false;
        }
        HonourImportService.PhotoFile file = photos.get(row.identityNumber());
        if (file == null) {
            return false;
        }

        try {
            // ⚠️ Through PhotoStorageService, so the archive's photographs meet
            // exactly the rules a single upload meets: JPEG or PNG, 600x800
            // minimum, portrait. A second validation path would eventually
            // disagree with the first, and a card printed from an undersized
            // photograph is unusable at a checkpoint.
            cardService.attachPhoto(card.getId(),
                    new InMemoryMultipartFile(file.filename(), file.content()));
            return true;

        } catch (RuntimeException e) {
            log.warn("HONOUR_IMPORT_PHOTO_FAILED card={} file={} reason={}",
                    card.getCardNumber(), file.filename(), e.getMessage());
            return false;
        }
    }

    /**
     * A MultipartFile over bytes already in hand.
     *
     * ⚠️ Exists so the import can use PhotoStorageService unchanged. The
     * alternative was a second method taking a byte[] — and then the
     * dimension checks, the type checks and the storage path would have two
     * implementations that drift.
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
