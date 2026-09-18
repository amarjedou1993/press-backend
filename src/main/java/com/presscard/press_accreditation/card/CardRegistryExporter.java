package com.presscard.press_accreditation.card;

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
 * The card registry, as a spreadsheet.
 *
 * HAPA needs this for distribution — calling holders in, ticking off
 * collections, and answering "is this person accredited" from a desk without
 * the system open.
 *
 * TWO CHOICES WORTH NAMING.
 *
 * · NNI IS INCLUDED. It is the identifier HAPA's other records key on, and a
 *   registry that cannot be joined to them is a registry nobody uses. The file
 *   is therefore PERSONAL DATA — the sheet says so in its own header, because
 *   a spreadsheet travels further than anyone intends.
 *
 * · THE VERIFICATION TOKEN IS NOT INCLUDED. It is the one field that would
 *   turn a leaked file into a way to look up every journalist's photograph.
 *   Whoever holds this file already has the data; nobody else should gain a
 *   lookup key from it.
 *
 * ───────────────────────────────────────────────────────────────────────
 * ⚠️ THIS CLASS NO LONGER READS A REPOSITORY, AND THAT IS THE CHANGE.
 *
 * It used to look up the application, the holder, the profile and the
 * category INSIDE its loop — four round trips per card, so a register of six
 * hundred made two thousand four hundred. Tolerable, because the export is
 * occasional.
 *
 * Then the procès-verbal needed exactly the same facts. Written the same way
 * it would have doubled the problem, and put it on a document the Ministry
 * produces every session.
 *
 * So the assembly moved to CardRegistryAssembler — four queries whatever the
 * size — and what remains here is rendering. It receives rows and writes a
 * workbook.
 *
 * ⚠️ NO REPOSITORY MAY BE INJECTED BACK. The cost would not show in a review;
 * it would show as an export that takes a minute.
 * ───────────────────────────────────────────────────────────────────────
 */
@Service
public class CardRegistryExporter {

    private static final DateTimeFormatter DATE_FR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    private final CardRegistryAssembler assembler;

    public CardRegistryExporter(CardRegistryAssembler assembler) {
        this.assembler = assembler;
    }

    private static final String[] HEADERS = {
            "N° de carte", "Nom complet", "NNI / Passeport", "Catégorie",
            "Téléphone", "E-mail", "Délivrée le", "Expire le", "Statut"
    };

    /** Series A — cards issued on a dossier. */
    @Transactional(readOnly = true)
    public byte[] export(List<Card> cards, String title) {
        return exportRows(assembler.fromCards(cards), title);
    }

    /**
     * Render rows already assembled.
     *
     * ⚠️ PUBLIC, because the honour and institutional registers reach it
     * through their own assembler methods. One workbook format across the
     * three series — a second would drift the day somebody adds a column.
     */
    @Transactional(readOnly = true)
    public byte[] exportRows(List<CardRegistryRow> rows, String title) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Cartes de presse");

            CellStyle titleStyle = boldStyle(workbook, 14);
            CellStyle noticeStyle = italicStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle dateStyle = plainStyle(workbook);

            int row = 0;

            // ── title ──
            Row titleRow = sheet.createRow(row++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

            // ── the warning the file must carry with it ──
            Row noticeRow = sheet.createRow(row++);
            Cell noticeCell = noticeRow.createCell(0);
            noticeCell.setCellValue(
                    "DOCUMENT INTERNE — contient des données personnelles (identité, "
                            + "coordonnées). Diffusion restreinte à la HAPA. Édité le "
                            + LocalDate.now().format(DATE_FR) + ".");
            noticeCell.setCellStyle(noticeStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, HEADERS.length - 1));

            row++;  // a blank line

            // ── headers ──
            Row headerRow = sheet.createRow(row++);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            /*
             * ── the cards ──
             *
             * ⚠️ NO LOOKUPS HERE. Every value comes from the row, which the
             * assembler built in four queries. The expiry derivation — a
             * lapsed card never reading "Valide" because a flag was not
             * updated — lives there too, so this and the procès-verbal cannot
             * disagree about a card's state.
             */
            for (CardRegistryRow r : rows) {
                Row dataRow = sheet.createRow(row++);
                int col = 0;
                write(dataRow, col++, r.cardNumber(), dateStyle);
                write(dataRow, col++, r.fullName(), dateStyle);
                write(dataRow, col++, r.identityNumber(), dateStyle);
                write(dataRow, col++, r.categoryLabelFr(), dateStyle);
                write(dataRow, col++, orDash(r.phone()), dateStyle);
                write(dataRow, col++, orDash(r.email()), dateStyle);
                write(dataRow, col++, r.issuedAt() == null ? "—"
                        : r.issuedAt().format(DATE_FR), dateStyle);
                write(dataRow, col++, r.expiresAt() == null ? "—"
                        : r.expiresAt().format(DATE_FR), dateStyle);
                write(dataRow, col, r.statusLabelFr(), dateStyle);
            }

            // A filter row so HAPA can sort by category or status immediately.
            sheet.setAutoFilter(new CellRangeAddress(3, Math.max(3, row - 1), 0, HEADERS.length - 1));
            sheet.createFreezePane(0, 4);

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
                // autoSizeColumn is tight to the point of unreadable.
                sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 900, 12000));
            }

            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException("Le registre n'a pas pu être exporté", e);
        }
    }

    /* ── styles ── */

    private static void write(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "—" : value);
        cell.setCellStyle(style);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

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
}