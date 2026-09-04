package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import com.presscard.press_accreditation.config.AppProperties;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reading a batch of honour cards out of a spreadsheet.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS SERVICE COMMITS NOTHING. It reads, checks, and reports.
 *
 * A bulk grant takes a card number per row from a sequence that must run
 * unbroken — the register's numbering is its own evidence. An import that
 * failed on row thirty after taking twenty-nine numbers would leave permanent
 * gaps, and a register with unexplained holes invites the question of what
 * was removed.
 *
 * So: parse, validate, return everything that would happen. The administrator
 * reads it and confirms, and only then does HonourImportCommitter take a
 * single number at a time through the ordinary grant path.
 * ───────────────────────────────────────────────────────────────────────
 *
 * ⚠️ AND THE PHOTOGRAPHS ARRIVE AS FILES IN A ZIP, not embedded in the sheet.
 *
 * POI can read pictures out of a workbook, but they are anchored to a REGION
 * rather than a cell — a picture floating across rows 7 and 8 has no definite
 * owner. The failure is silent: it does not error, it attaches one man's face
 * to another woman's card, and that is discovered at a checkpoint.
 *
 * A ZIP names each photograph by its holder's identity number. The join is
 * explicit, the folder can be opened and looked at before anything is
 * uploaded, and every file goes through PhotoStorageService's real checks
 * rather than a second implementation of them.
 */
@Service
public class HonourImportService {

    private static final Logger log = LoggerFactory.getLogger("HONOUR_IMPORT");

    /** Where the workbook must sit inside the archive. */
    private static final String WORKBOOK_SUFFIX = ".xlsx";
    private static final String PHOTO_PREFIX = "photos/";

    /**
     * The archive ceiling.
     *
     * ───────────────────────────────────────────────────────────────────
     * ⚠️ TEN MEGABYTES, MATCHING spring.servlet.multipart.max-file-size —
     * and the two must stay equal.
     *
     * The tempting fix was to raise the global multipart limit so a bigger
     * archive could arrive. That limit is not this feature's: it applies to
     * every upload in the system, including a candidate's. Raising it to
     * 200MB would let any account holder post 200MB, buffered before any of
     * this code runs — a memory exhaustion anyone with a login could trigger,
     * opened permanently for a feature the Ministry uses twice a year.
     *
     * Ten megabytes holds roughly forty cards: an ICAO portrait at 600x800 is
     * typically 100-300KB. An honour card batch is a set of exceptional
     * grants, not a cohort, so that is ample — and a larger batch is two
     * uploads, which is a mild inconvenience twice a year against a
     * permanently open vector.
     *
     * If HAPA genuinely needs larger batches, the answer is streaming the
     * archive to disk instead of holding it in memory. That is a different
     * piece of work, and worth doing properly rather than by raising a
     * number.
     * ───────────────────────────────────────────────────────────────────
     */
    private static final long MAX_ARCHIVE_BYTES = 10L * 1024 * 1024;

    /**
     * The columns, in order.
     *
     * ⚠️ MATCHED BY POSITION, and the header row is only checked to catch a
     * file that is obviously not the template. Matching by header text would
     * break the moment someone renames a column or Excel adds a space.
     */
    private static final String[] HEADERS = {
            "Nom complet", "NNI / Passeport", "Date de naissance",
            "Lieu de naissance", "Catégorie", "Spécialité",
            "Organe de presse", "Expire le", "Motif de l'octroi"
    };

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/yyyy"),
    };

    private final HonourCardRepository repository;
    private final PressCategoryRepository categoryRepository;
    private final SpecialisationRepository specialisationRepository;
    private final AppProperties props;

    public HonourImportService(HonourCardRepository repository,
                               PressCategoryRepository categoryRepository,
                               SpecialisationRepository specialisationRepository,
                               AppProperties props) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.specialisationRepository = specialisationRepository;
        this.props = props;
    }

    /* ══ what a preview returns ══ */

    /** One row, as read and as judged. */
    public record ImportRow(
            int rowNumber,
            String fullName,
            String identityNumber,
            LocalDate birthdate,
            String birthplace,
            Long categoryId,
            String categoryLabelFr,
            Long specialisationId,
            String specialisationLabelFr,
            String institution,
            LocalDate expiresAt,
            String grantReason,
            /** True when a photograph for this identity was found in the ZIP. */
            boolean hasPhoto,
            /**
             * Why this row cannot be granted, or null.
             *
             * ⚠️ A MISSING PHOTOGRAPH IS NOT AN ERROR. The card is granted and
             * waits; the Ministry attaches the photograph afterwards, and only
             * then does it reach the printer. Refusing the whole row would
             * force a second import for the same people.
             */
            String errorFr,
            /** Not blocking, but the administrator should know. */
            String warningFr
    ) {
        public boolean valid() { return errorFr == null; }
    }

    public record ImportPreview(
            int totalRows,
            int validRows,
            int rowsWithPhoto,
            int rejectedRows,
            List<ImportRow> rows,
            /** Problems with the archive itself, not with any one row. */
            List<String> fileErrorsFr
    ) {}

    /** The archive, held in memory between the preview and the commit. */
    public record ParsedArchive(
            ImportPreview preview,
            /** identityNumber → photo bytes, and its filename for the extension. */
            Map<String, PhotoFile> photos
    ) {}

    public record PhotoFile(String filename, byte[] content) {}

    /* ══ the parse ══ */

    @Transactional(readOnly = true)
    public ParsedArchive parse(MultipartFile archive) {
        List<String> fileErrors = new ArrayList<>();

        if (archive == null || archive.isEmpty()) {
            throw new HonourImportException("Aucun fichier reçu.");
        }
        if (archive.getSize() > MAX_ARCHIVE_BYTES) {
            // The way out is named, not merely the refusal: an administrator
            // told only "too large" has to guess what to do next.
            throw new HonourImportException(
                    "L'archive dépasse 10 Mo. Répartissez les cartes sur "
                            + "plusieurs imports — une quarantaine par archive.");
        }

        byte[] workbookBytes = null;
        Map<String, PhotoFile> photos = new HashMap<>();

        /*
         * ⚠️ ONE PASS over the archive, and everything held in memory.
         *
         * A ZIP stream cannot be rewound, and the workbook may come after the
         * photographs. Sixty megabytes is the ceiling precisely because this
         * is not streamed to disk — a limit chosen so the failure is a clear
         * refusal rather than a heap exhaustion under load.
         */
        try (ZipInputStream zip = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');

                // ⚠️ Path traversal: a crafted entry named "../../etc/passwd"
                // is a real archive attack. Nothing here writes to disk by
                // entry name, but the guard costs one line and the next
                // version of this code might.
                if (name.contains("..")) {
                    fileErrors.add("Entrée d'archive refusée : " + entry.getName());
                    continue;
                }

                byte[] content = zip.readAllBytes();

                if (name.toLowerCase(Locale.ROOT).endsWith(WORKBOOK_SUFFIX)
                        && !name.startsWith(PHOTO_PREFIX)) {
                    if (workbookBytes != null) {
                        fileErrors.add("L'archive contient plusieurs classeurs .xlsx.");
                    }
                    workbookBytes = content;

                } else if (name.startsWith(PHOTO_PREFIX)) {
                    String filename = name.substring(PHOTO_PREFIX.length());
                    String key = stripExtension(filename).replaceAll("\\s", "");
                    if (!key.isBlank()) {
                        photos.put(key, new PhotoFile(filename, content));
                    }
                }
            }
        } catch (IOException e) {
            throw new HonourImportException("L'archive n'a pas pu être lue.");
        }

        if (workbookBytes == null) {
            throw new HonourImportException(
                    "Aucun classeur .xlsx trouvé dans l'archive. "
                            + "Placez le fichier à la racine, et les photographies dans "
                            + "un dossier « photos ».");
        }

        List<ImportRow> rows = readWorkbook(workbookBytes, photos.keySet(), fileErrors);

        int valid = (int) rows.stream().filter(ImportRow::valid).count();
        int withPhoto = (int) rows.stream()
                .filter(r -> r.valid() && r.hasPhoto()).count();

        log.info("HONOUR_IMPORT_PREVIEW rows={} valid={} withPhoto={} photos={}",
                rows.size(), valid, withPhoto, photos.size());

        return new ParsedArchive(
                new ImportPreview(rows.size(), valid, withPhoto,
                        rows.size() - valid, rows, fileErrors),
                photos);
    }

    /* ══ the workbook ══ */

    private List<ImportRow> readWorkbook(byte[] bytes, Set<String> photoKeys,
                                         List<String> fileErrors) {
        List<ImportRow> rows = new ArrayList<>();

        // Both catalogues read once, and matched on CODE or LABEL — an
        // administrator filling a spreadsheet types what they read on screen,
        // and a code they have never seen would be a trap.
        Map<String, PressCategory> categories = new HashMap<>();
        for (PressCategory c : categoryRepository.findAll()) {
            categories.put(normalise(c.getCode()), c);
            categories.put(normalise(c.getLabelFr()), c);
        }
        Map<String, Specialisation> specialisations = new HashMap<>();
        for (Specialisation s : specialisationRepository.findAll()) {
            specialisations.put(normalise(s.getCode()), s);
            specialisations.put(normalise(s.getLabelFr()), s);
        }

        try (InputStream in = new ByteArrayInputStream(bytes);
             Workbook workbook = new XSSFWorkbook(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.FRENCH);

            int headerRow = findHeaderRow(sheet, formatter);
            if (headerRow < 0) {
                throw new HonourImportException(
                        "En-têtes introuvables. Utilisez le modèle fourni : la ligne "
                                + "d'en-tête doit commencer par « Nom complet ».");
            }

            // Every identity in the file, checked against the register in ONE
            // query rather than one per row.
            Set<String> fileIdentities = new HashSet<>();
            List<String[]> raw = new ArrayList<>();

            for (int i = headerRow + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String[] cells = new String[HEADERS.length];
                boolean empty = true;
                for (int c = 0; c < HEADERS.length; c++) {
                    cells[c] = cellText(row.getCell(c), formatter);
                    if (!cells[c].isBlank()) empty = false;
                }
                if (empty) continue;   // a blank separator row is not an error

                raw.add(cells);
            }

            List<String> identities = raw.stream()
                    .map(c -> c[1].replaceAll("\\s", ""))
                    .filter(s -> !s.isBlank())
                    .toList();

            Set<String> alreadyHeld = identities.isEmpty()
                    ? Set.of()
                    : new HashSet<>(repository.findExistingIdentityNumbers(identities));

            int rowNumber = headerRow + 2;   // 1-based, as Excel shows it
            for (String[] cells : raw) {
                rows.add(toRow(rowNumber++, cells, categories, specialisations,
                        photoKeys, alreadyHeld, fileIdentities));
            }

        } catch (HonourImportException e) {
            throw e;
        } catch (Exception e) {
            log.warn("HONOUR_IMPORT_WORKBOOK_FAILED", e);
            throw new HonourImportException(
                    "Le classeur n'a pas pu être lu. Vérifiez qu'il s'agit bien "
                            + "d'un fichier .xlsx et non d'un .xls ou d'un .csv renommé.");
        }

        return rows;
    }

    private ImportRow toRow(int rowNumber, String[] cells,
                            Map<String, PressCategory> categories,
                            Map<String, Specialisation> specialisations,
                            Set<String> photoKeys,
                            Set<String> alreadyHeld,
                            Set<String> seenInFile) {

        String fullName = cells[0].trim();
        String identity = cells[1].replaceAll("\\s", "");
        String birthplace = cells[3].trim();
        String institution = cells[6].trim();
        String grantReason = cells[8].trim();

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (fullName.isBlank()) errors.add("nom manquant");

        /*
         * ⚠️ THE IDENTITY NUMBER IS NOT PAPERWORK. The card's signature is
         * computed over it — without one the card cannot be signed, and a
         * scan reports the Ministry's own credential as unverifiable.
         */
        if (identity.isBlank()) {
            errors.add("NNI ou passeport manquant");
        } else {
            if (identity.matches("\\d{10}")
                    && props.identity().nniChecksum()
                    && (Long.parseLong(identity) - 1) % 97 != 0) {
                errors.add("NNI invalide (clé de contrôle)");
            }
            if (alreadyHeld.contains(identity)) {
                errors.add("une carte d'honneur existe déjà pour ce numéro");
            }
            if (!seenInFile.add(identity)) {
                errors.add("ce numéro apparaît plusieurs fois dans le fichier");
            }
        }

        LocalDate birthdate = parseDate(cells[2]);
        if (!cells[2].isBlank() && birthdate == null) {
            warnings.add("date de naissance illisible, ignorée");
        }

        LocalDate expiresAt = parseDate(cells[7]);
        if (cells[7].isBlank()) {
            errors.add("date d'expiration manquante");
        } else if (expiresAt == null) {
            errors.add("date d'expiration illisible (attendu : jj/mm/aaaa)");
        } else if (!expiresAt.isAfter(LocalDate.now())) {
            errors.add("la date d'expiration est passée");
        }

        // ⚠️ Mandatory, for the reason a justification is mandatory on a
        // rejection: this card bypasses the examination every other card
        // requires, and the register must say why.
        if (grantReason.isBlank()) errors.add("motif de l'octroi manquant");

        PressCategory category = categories.get(normalise(cells[4]));
        if (!cells[4].isBlank() && category == null) {
            warnings.add("catégorie « " + cells[4].trim() + " » inconnue, ignorée");
        }
        Specialisation specialisation = specialisations.get(normalise(cells[5]));
        if (!cells[5].isBlank() && specialisation == null) {
            warnings.add("spécialité « " + cells[5].trim() + " » inconnue, ignorée");
        }

        boolean hasPhoto = !identity.isBlank() && photoKeys.contains(identity);
        if (!hasPhoto && errors.isEmpty()) {
            // ⚠️ A WARNING, NEVER AN ERROR. The card is granted and waits; the
            // Ministry attaches the photograph afterwards, and only then does
            // it reach the printer. Refusing the row would force a second
            // import for the same people.
            warnings.add("photographie absente — à ajouter avant production");
        }

        return new ImportRow(
                rowNumber, fullName, identity, birthdate,
                birthplace.isBlank() ? null : birthplace,
                category == null ? null : category.getId(),
                category == null ? null : category.getLabelFr(),
                specialisation == null ? null : specialisation.getId(),
                specialisation == null ? null : specialisation.getLabelFr(),
                institution.isBlank() ? null : institution,
                expiresAt, grantReason, hasPhoto,
                errors.isEmpty() ? null : String.join(" ; ", errors),
                warnings.isEmpty() ? null : String.join(" ; ", warnings));
    }

    /* ══ internals ══ */

    /** The header row, wherever the template's title block put it. */
    private static int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        for (int i = 0; i <= Math.min(sheet.getLastRowNum(), 20); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String first = cellText(row.getCell(0), formatter);
            if (normalise(first).equals(normalise(HEADERS[0]))) {
                return i;
            }
        }
        return -1;
    }

//    private static String cellText(Cell cell, DataFormatter formatter) {
//        if (cell == null) return "";
//        // ⚠️ A date cell is NUMERIC in Excel. DataFormatter renders it with
//        // the cell's own format, which is why the date parser below accepts
//        // several shapes rather than one.
//        return formatter.formatCellValue(cell).trim();
//    }

    /**
     * A cell as text — except a date, which is read as a date.
     *
     * ⚠️ AN EXCEL DATE IS A NUMBER, and DataFormatter renders it with
     * whatever format the cell happens to carry: "03/09/2028", "2028-09-03
     * 00:00:00", "3-sept-28". Matching those renderings against a list of
     * patterns is a guess that fails on the first machine with different
     * regional settings.
     *
     * So a date-formatted numeric cell is converted to ISO here, which
     * parseDate then reads exactly. Text cells still go through the pattern
     * list, because a column typed by hand is genuinely text.
     */
    private static String cellText(Cell cell, DataFormatter formatter) {
        if (cell == null) return "";

        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

//    /** Case- and accent-insensitive, so "Journaliste" matches "journaliste". */
//    private static String normalise(String value) {
//        return value == null ? "" : value.trim().toLowerCase(Locale.FRENCH);
//    }

    /**
     * Case-insensitive, and ASTERISK-INSENSITIVE.
     *
     * ⚠️ The template marks mandatory columns with " *" — and matching the
     * literal string meant the parser never recognised its own template's
     * header row. Stripping the marker here rather than duplicating it in
     * HEADERS keeps the two arrays from having to agree on decoration as well
     * as on order.
     */
    private static String normalise(String value) {
        return value == null ? ""
                : value.replace("*", "").trim().toLowerCase(Locale.FRENCH);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}