package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.card.QrCodeService;
import com.presscard.press_accreditation.storage.PhotoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The production archive for honour cards.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ NO PDF PREVIEW, UNLIKE AN ORDINARY CARD'S ARCHIVE.
 *
 * There is nothing to render. CardPdfService lays out a card from a dossier,
 * and an honour card has none — the template reaches for an application and
 * throws when it finds nothing.
 *
 * Which turns out to suit the requirement: the producer asked for the
 * photograph and the QR, and those are what leave. One less artefact carrying
 * the Ministry's layout out of the building.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class HonourArchiveService {

    private static final Logger log = LoggerFactory.getLogger("HONOUR_ARCHIVE");

    private final HonourCardRepository repository;
    private final QrCodeService qrCodeService;
    private final PhotoStorageService photoStorage;

    public HonourArchiveService(HonourCardRepository repository,
                                QrCodeService qrCodeService,
                                PhotoStorageService photoStorage) {
        this.repository = repository;
        this.qrCodeService = qrCodeService;
        this.photoStorage = photoStorage;
    }

    /** What went into the archive, and what did not. */
    public record ArchiveResult(byte[] zip, int included, int skipped) {}

    @Transactional(readOnly = true)
    public ArchiveResult archive(List<Long> cardIds) {
        List<HonourCard> cards = repository.findAllById(cardIds);
        int included = 0;
        int skipped = 0;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {

            StringBuilder manifest = new StringBuilder(
                    "numero;dossier;nom;identite;organe;delivree_le;expire_le\n");

            for (HonourCard card : cards) {
                String folder = folderName(card.getCardNumber());
                boolean wrote = false;

                /* ── the photograph ── */
                if (card.getPhotoPath() != null) {
                    try {
                        Path photo = photoStorage.resolve(card.getPhotoPath());
                        if (Files.exists(photo)) {
                            String ext = extensionOf(photo.getFileName().toString());
                            put(zip, folder + "/" + folder + "-photo" + ext,
                                Files.readAllBytes(photo));
                            wrote = true;
                        }
                    } catch (Exception e) {
                        log.warn("HONOUR_ARCHIVE_PHOTO_FAILED card={} reason={}",
                                card.getCardNumber(), e.getMessage());
                    }
                }

                /* ── the verification QR ── */
                try {
                    // ⚠️ verificationUrl(), not the bare token. qrPng takes the
                    // CONTENT to encode: passing the token compiles and
                    // produces a valid QR encoding a meaningless string that
                    // resolves to nothing — discovered on printed cards, at a
                    // checkpoint.
                    put(zip, folder + "/" + folder + "-qr.png",
                        qrCodeService.qrPng(
                            qrCodeService.verificationUrl(card.getVerificationToken())));
                    wrote = true;
                } catch (Exception e) {
                    log.warn("HONOUR_ARCHIVE_QR_FAILED card={} reason={}",
                            card.getCardNumber(), e.getMessage());
                }

                if (wrote) {
                    included++;
                } else {
                    skipped++;
                    log.error("HONOUR_ARCHIVE_EMPTY card={} — no photo, no QR",
                            card.getCardNumber());
                }

                manifest.append(csv(card.getCardNumber())).append(';')
                        .append(csv(folder)).append(';')
                        .append(csv(card.getFullName())).append(';')
                        .append(csv(card.getIdentityNumber())).append(';')
                        .append(csv(card.getInstitution())).append(';')
                        .append(card.getIssuedAt()).append(';')
                        .append(card.getExpiresAt()).append('\n');
            }

            /* A BOM and semicolons: Excel on a French Windows install reads a
               comma-separated file as one column and mangles accented names
               without the byte-order mark. The person opening this is a card
               designer, not a developer. */
            put(zip, "cartes-honneur.csv",
                ("\uFEFF" + manifest).getBytes(StandardCharsets.UTF_8));

            zip.finish();
            log.info("HONOUR_ARCHIVE requested={} included={} skipped={}",
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
     * "B - 0001 / 26" → "B-0001-26"
     *
     * ⚠️ THE SLASH IS WHY THIS EXISTS. A "/" inside a ZIP entry name IS a
     * directory separator: left alone, one card would extract as a folder
     * "B - 0001 " containing a folder " 26", repeated for every card in the
     * batch.
     *
     * The unsanitised number stays in the manifest, so the mapping back to the
     * register is never lost.
     */
    static String folderName(String cardNumber) {
        if (cardNumber == null || cardNumber.isBlank()) {
            return "sans-numero";
        }
        return cardNumber
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : ".jpg";
    }

    private static String csv(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        return escaped.contains(";") || escaped.contains("\"") || escaped.contains("\n")
                ? "\"" + escaped + "\""
                : escaped;
    }
}
