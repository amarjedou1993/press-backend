package com.presscard.press_accreditation.card;

import com.presscard.press_accreditation.application.ApplicationRepository;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import com.presscard.press_accreditation.user.User;
import com.presscard.press_accreditation.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The production archive: one folder per card, carrying what a card designer
 * needs to lay out and print it.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THE QR MUST COME FROM THE SAME CODE PATH AS THE CARD'S.
 *
 * A QR regenerated with different error correction, a different quiet zone
 * or a different module size can fail to scan on the same printed stock —
 * and the failure appears at a checkpoint, months later, on a card already in
 * someone's pocket.
 *
 * So this service does not generate one. It makes the SAME CALL CardPdfService
 * makes — QrCodeService.qrPng, already 600px at error correction Q — so the
 * archive's QR and the card's are the same bytes, not merely similar.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class CardArchiveService {

    private static final Logger log = LoggerFactory.getLogger("CARD_ARCHIVE");

    private final CardRepository cardRepository;
    private final CardPdfService pdfService;
    private final QrCodeService qrCodeService;
    private final PhotoStorageService photoStorage;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;

    public CardArchiveService(CardRepository cardRepository,
                              CardPdfService pdfService,
                              QrCodeService qrCodeService,
                              PhotoStorageService photoStorage,
                              ApplicationRepository applicationRepository,
                              UserRepository userRepository) {
        this.cardRepository = cardRepository;
        this.pdfService = pdfService;
        this.qrCodeService = qrCodeService;
        this.photoStorage = photoStorage;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
    }

    /** What went into the archive, and what did not. */
    public record ArchiveResult(byte[] zip, int included, int skipped) {}

    @Transactional
    public ArchiveResult archive(List<Long> cardIds) {
        List<Card> cards = cardRepository.findAllById(cardIds);
        int included = 0;
        int skipped = 0;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

            StringBuilder manifest = new StringBuilder(
                    "numero;dossier;nom;categorie;organe;delivree_le;expire_le\n");

            for (Card card : cards) {
                String folder = folderName(card.getCardNumber());
                boolean wrote = false;

                /* ── the photograph, as issued ── */
                if (card.getPhotoPath() != null) {
                    try {
                        Path photo = photoStorage.resolve(card.getPhotoPath());
                        if (Files.exists(photo)) {
                            // ⚠️ The extension follows the STORED file, not a
                            // guess: a PNG renamed .jpg opens in a browser and
                            // fails in half the layout tools a designer uses.
                            String ext = extensionOf(photo.getFileName().toString());
                            put(zip, folder + "/" + folder + "-photo" + ext,
                                    Files.readAllBytes(photo));
                            wrote = true;
                        }
                    } catch (Exception e) {
                        log.warn("ARCHIVE_PHOTO_FAILED card={} reason={}",
                                card.getCardNumber(), e.getMessage());
                    }
                }

                /* ── the verification QR ── */
                if (card.getVerificationToken() != null) {
                    try {
                        // ⚠️ verificationUrl(), NOT the bare token.
                        //
                        // qrPng takes the CONTENT to encode. Passing the token
                        // straight in compiles and produces a perfectly valid
                        // QR — encoding a meaningless opaque string that
                        // resolves to nothing when scanned. The failure would
                        // appear on printed cards, at a checkpoint.
                        //
                        // Same size and error correction as the card's own,
                        // because it is literally the same call.
                        put(zip, folder + "/" + folder + "-qr.png",
                                qrCodeService.qrPng(
                                        qrCodeService.verificationUrl(card.getVerificationToken())));
                        wrote = true;
                    } catch (Exception e) {
                        log.warn("ARCHIVE_QR_FAILED card={} reason={}",
                                card.getCardNumber(), e.getMessage());
                    }
                }

                /* ── the rendered card, for reference ── */
                try {
                    put(zip, folder + "/" + folder + "-apercu.pdf",
                            pdfService.render(card.getId()));
                    wrote = true;
                } catch (Exception e) {
                    log.warn("ARCHIVE_PDF_FAILED card={} reason={}",
                            card.getCardNumber(), e.getMessage());
                }

                if (wrote) {
                    included++;
                    // ⚠️ Counted here, not in the controller: an archive that
                    // failed to build must not record that it was taken.
                    card.setArchiveCount(card.getArchiveCount() + 1);
                    card.setArchivedAt(OffsetDateTime.now());
                    cardRepository.save(card);
                } else {
                    skipped++;
                    log.error("ARCHIVE_EMPTY card={} — no photo, no QR, no PDF",
                            card.getCardNumber());
                }

                manifest.append(csv(card.getCardNumber())).append(';')
                        .append(csv(folder)).append(';')
                        .append(csv(nameOf(card))).append(';')
                        .append(csv(card.getSpecialisationFr())).append(';')
                        .append(csv(card.getInstitution())).append(';')
                        .append(card.getIssuedAt()).append(';')
                        .append(card.getExpiresAt()).append('\n');
            }

            /* ── the manifest ──
                 ⚠️ A BOM, and semicolons rather than commas. Excel on a French
                 Windows install reads a comma-separated file as one column and
                 mangles accented names without the byte-order mark. The person
                 opening this is a card designer, not a developer. */
            put(zip, "cartes.csv",
                    ("\uFEFF" + manifest).getBytes(StandardCharsets.UTF_8));

            zip.finish();
            log.info("CARD_ARCHIVE requested={} included={} skipped={}",
                    cardIds.size(), included, skipped);

            return new ArchiveResult(out.toByteArray(), included, skipped);

        } catch (IOException e) {
            throw new IllegalStateException("L'archive n'a pas pu être constituée", e);
        }
    }

    /* ══ internals ══ */

    private static void put(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /**
     * "A - 0001 / 26" → "A-0001-26"
     *
     * ⚠️ THE SLASH IS THE WHOLE REASON THIS METHOD EXISTS.
     *
     * A "/" inside a ZIP entry name IS a directory separator. Left alone, the
     * card number would extract as a folder "A - 0001 " containing a folder
     * " 26" — one card split across two nested directories, repeated for every
     * card in the batch.
     *
     * The spaces go too: a designer scripting an import over a folder full of
     * names with spaces is a shell-quoting problem nobody asked for.
     *
     * The unsanitised number stays in the manifest, so the mapping back to the
     * register is never lost.
     */
    static String folderName(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "sans-numero";
        }
        return cardNumber
                .replaceAll("[^A-Za-z0-9]+", "-")   // slash, spaces, anything else
                .replaceAll("^-+|-+$", "")          // no leading or trailing dash
                .toUpperCase(Locale.ROOT);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : ".jpg";
    }

    /**
     * The holder's name.
     *
     * ⚠️ NOT on the Card. Unlike the photograph, the specialisation and the
     * outlet — all snapshotted at issuance — the name lives only on the user,
     * reached through the application. So it is resolved here, and a card
     * whose chain is broken gets an empty cell rather than an exception: a
     * manifest that fails to build helps nobody.
     */
    private String nameOf(Card card) {
        return applicationRepository.findById(card.getApplicationId())
                .flatMap(a -> userRepository.findById(a.getCandidateId()))
                .map(User::getFullName)
                .orElse("");
    }

    /** Semicolon-separated, so a value containing one must be quoted. */
    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(";") || escaped.contains("\"") || escaped.contains("\n")
                ? "\"" + escaped + "\""
                : escaped;
    }
}