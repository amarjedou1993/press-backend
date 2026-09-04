package com.presscard.press_accreditation.honour;

import com.presscard.press_accreditation.category.PressCategory;
import com.presscard.press_accreditation.category.PressCategoryRepository;
import com.presscard.press_accreditation.category.Specialisation;
import com.presscard.press_accreditation.category.SpecialisationRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The blank workbook an administrator fills in.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS IS NOT A CONVENIENCE. Without it the first import fails on column
 * order, and nothing tells the administrator what the columns should be — a
 * format nobody can produce is a feature nobody can use.
 *
 * It carries three things a bare header row would not:
 *
 *  · THE REFERENCE LISTS, on a second sheet. Categories and specialisations
 *    are closed vocabularies; typing "photographe" when the list says
 *    "Photographe de presse" produces a card with no category, and the
 *    administrator learns it from a warning rather than from the form.
 *
 *  · THE PHOTOGRAPH CONVENTION, stated where it will be read. The join is a
 *    filename, and a filename convention nobody was told is a convention
 *    nobody follows.
 *
 *  · WHAT IS MANDATORY, marked in the header itself.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class HonourImportTemplate {

    private static final DateTimeFormatter DATE_FR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    /**
     * ⚠️ THE SAME ORDER AS HonourImportService.HEADERS, and the parser reads
     * BY POSITION. Changing one without the other silently maps every column
     * to its neighbour — names into identity numbers, dates into reasons.
     */
    private static final String[] HEADERS = {
            "Nom complet *", "NNI / Passeport *", "Date de naissance",
            "Lieu de naissance", "Catégorie", "Spécialité",
            "Organe de presse", "Expire le *", "Motif de l'octroi *"
    };

    private final PressCategoryRepository categoryRepository;
    private final SpecialisationRepository specialisationRepository;

    public HonourImportTemplate(PressCategoryRepository categoryRepository,
                                SpecialisationRepository specialisationRepository) {
        this.categoryRepository = categoryRepository;
        this.specialisationRepository = specialisationRepository;
    }

    @Transactional(readOnly = true)
    public byte[] build() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            buildEntrySheet(workbook);
            buildReferenceSheet(workbook);

            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException("Le modèle n'a pas pu être généré", e);
        }
    }

    /* ══ sheet 1: what they fill in ══ */

    private void buildEntrySheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Cartes d'honneur");

        CellStyle titleStyle = boldStyle(workbook, 14);
        CellStyle noticeStyle = italicStyle(workbook);
        CellStyle headerStyle = headerStyle(workbook);
        CellStyle exampleStyle = exampleStyle(workbook);

        int row = 0;

        Row titleRow = sheet.createRow(row++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Cartes d'honneur — import en masse");
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

        /*
         * ⚠️ THE INSTRUCTIONS SIT ABOVE THE HEADERS, where they will be read.
         *
         * The parser looks for its header row anywhere in the first twenty
         * lines precisely so this block can exist — an instruction on a
         * separate sheet is an instruction nobody opens.
         */
        for (String line : List.of(
                "Remplissez une ligne par carte. Les colonnes marquées * sont obligatoires.",
                "PHOTOGRAPHIES : placez-les dans un dossier « photos », nommées par le NNI "
                + "ou le numéro de passeport — par exemple photos/1234567890.jpg.",
                "Compressez ensuite ce classeur ET le dossier « photos » dans une seule "
                + "archive .zip (10 Mo maximum, une quarantaine de cartes).",
                "Une carte sans photographie est tout de même créée : elle attend son "
                + "image avant de pouvoir être produite.",
                "Catégories et spécialités : voir la feuille « Références ». "
                + "Une valeur inconnue est ignorée, la carte est créée sans elle.")) {
            Row noticeRow = sheet.createRow(row);
            Cell cell = noticeRow.createCell(0);
            cell.setCellValue(line);
            cell.setCellStyle(noticeStyle);
            sheet.addMergedRegion(new CellRangeAddress(row, row, 0, HEADERS.length - 1));
            row++;
        }

        row++;   // a blank line

        Row headerRow = sheet.createRow(row++);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        /*
         * ⚠️ ONE EXAMPLE ROW, in grey italics.
         *
         * It shows the date format, which is the single most likely thing to
         * be got wrong — and an administrator who deletes it has still seen
         * jj/mm/aaaa once.
         *
         * The parser skips blank rows, so a template returned untouched
         * imports exactly this one person. That is the risk; it is worth
         * accepting because a template with no example produces a file with
         * the wrong dates, which is worse and quieter.
         */
        Row example = sheet.createRow(row);
        String[] values = {
//                "Mohamed Ould Ahmed", "1234567890",
                "EXEMPLE — supprimez cette ligne", "1234567890",
                "15/03/1975", "Nouakchott",
                firstCategoryLabel(), firstSpecialisationLabel(),
                "Agence Mauritanienne d'Information",
                LocalDate.now().plusYears(2).format(DATE_FR),
                "Décision ministérielle n° … du …"
        };
        for (int i = 0; i < values.length; i++) {
            Cell cell = example.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(exampleStyle);
        }

        for (int i = 0; i < HEADERS.length; i++) {
            sheet.autoSizeColumn(i);
            // autoSizeColumn is tight to the point of unreadable.
            sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 900, 12000));
        }
    }

    /* ══ sheet 2: the closed vocabularies ══ */

    private void buildReferenceSheet(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Références");

        CellStyle headerStyle = headerStyle(workbook);
        CellStyle plainStyle = plainStyle(workbook);
        CellStyle noticeStyle = italicStyle(workbook);

        int row = 0;

        Row notice = sheet.createRow(row++);
        Cell noticeCell = notice.createCell(0);
        noticeCell.setCellValue(
                "Copiez la valeur exacte dans la feuille précédente. "
              + "Le code fonctionne aussi bien que le libellé.");
        noticeCell.setCellStyle(noticeStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));

        row++;

        Row header = sheet.createRow(row++);
        writeCell(header, 0, "Catégories", headerStyle);
        writeCell(header, 1, "Code", headerStyle);

        for (PressCategory category : categoryRepository.findAll()) {
            Row line = sheet.createRow(row++);
            writeCell(line, 0, category.getLabelFr(), plainStyle);
            writeCell(line, 1, category.getCode(), plainStyle);
        }

        row++;

        Row specHeader = sheet.createRow(row++);
        writeCell(specHeader, 0, "Spécialités", headerStyle);
        writeCell(specHeader, 1, "Code", headerStyle);

        for (Specialisation specialisation : specialisationRepository.findAll()) {
            Row line = sheet.createRow(row++);
            writeCell(line, 0, specialisation.getLabelFr(), plainStyle);
            writeCell(line, 1, specialisation.getCode(), plainStyle);
        }

        sheet.setColumnWidth(0, 10000);
        sheet.setColumnWidth(1, 6000);
    }

    /* ══ internals ══ */

    private String firstCategoryLabel() {
        return categoryRepository.findAll().stream()
                .findFirst().map(PressCategory::getLabelFr).orElse("");
    }

    private String firstSpecialisationLabel() {
        return specialisationRepository.findAll().stream()
                .findFirst().map(Specialisation::getLabelFr).orElse("");
    }

    private static void writeCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    /* ── styles, matching CardRegistryExporter's ── */

    private static CellStyle boldStyle(Workbook workbook, int points) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) points);
        style.setFont(font);
        return style;
    }

    private static CellStyle italicStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setFontHeightInPoints((short) 9);
        style.setFont(font);
        return style;
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.MEDIUM);
        return style;
    }

    private static CellStyle plainStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.HAIR);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle exampleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setItalic(true);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setBorderBottom(BorderStyle.HAIR);
        return style;
    }
}
