package com.presscard.press_accreditation.session;

import com.presscard.press_accreditation.error.SessionNotFoundException;
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
 * A session's results, as the file HAPA reports with.
 *
 * TWO SHEETS, because two different people open this workbook.
 *
 *   SYNTHÈSE  — the figures. Someone writing an annual report wants the
 *               totals, the acceptance rate and the objection rate, without
 *               scrolling past three hundred names to find them.
 *
 *   CANDIDATS — the cohort, one row each. Someone answering "was X accredited
 *               in 2026" wants exactly this, filterable.
 *
 * DELIBERATELY NARROWER THAN THE CARD REGISTRY. That file carries telephone
 * numbers and e-mail addresses because it is the list an administrator works
 * from when contacting card holders. This one is a RECORD OF DECISIONS: the
 * outcome of each candidature, not a way to reach anybody. Contact details
 * would widen an already sensitive file for no purpose it serves.
 *
 * Styled to match CardRegistryExporter — the same title, the same internal
 * notice, the same dark-green headers and hairline rows. Two exports from one
 * authority should not look like they came from two systems.
 */
@Service
public class SessionResultsExporter {

    private static final DateTimeFormatter DATE_FR =
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRENCH);

    private static final String[] HEADERS = {
            "Nom complet", "Catégorie", "Spécialité", "Organe de presse",
            "Résultat", "Déposée le", "Réclamation", "N° de carte", "Statut de la carte"
    };

    private final SessionRepository sessionRepository;
    private final SessionResultsService resultsService;

    public SessionResultsExporter(SessionRepository sessionRepository,
                                  SessionResultsService resultsService) {
        this.sessionRepository = sessionRepository;
        this.resultsService = resultsService;
    }

    @Transactional(readOnly = true)
    public byte[] export(Long sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId));

        SessionResultsService.SessionResults results = resultsService.results(sessionId);
        List<SessionResultsService.CandidateOutcome> candidates =
                resultsService.candidates(sessionId);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle titleStyle = boldStyle(workbook, 14);
            CellStyle sectionStyle = boldStyle(workbook, 11);
            CellStyle noticeStyle = italicStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle plainStyle = plainStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);

            writeSummary(workbook, session, results,
                    titleStyle, sectionStyle, noticeStyle, headerStyle,
                    plainStyle, numberStyle);

            writeCandidates(workbook, session, candidates,
                    titleStyle, noticeStyle, headerStyle, plainStyle);

            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Les résultats de la session n'ont pas pu être exportés", e);
        }
    }

    /* ══ sheet 1 — the figures ══════════════════════════════════ */

    private void writeSummary(Workbook workbook,
                              Session session,
                              SessionResultsService.SessionResults r,
                              CellStyle titleStyle, CellStyle sectionStyle,
                              CellStyle noticeStyle, CellStyle headerStyle,
                              CellStyle plainStyle, CellStyle numberStyle) {

        Sheet sheet = workbook.createSheet("Synthèse");
        int row = 0;

        row = writeTitle(sheet, row, titleStyle, noticeStyle,
                "Session n° %d — résultats".formatted(session.getId()),
                ("DOCUMENT INTERNE — récapitulatif des décisions rendues. "
               + "Diffusion restreinte à la HAPA. Édité le %s.")
                        .formatted(LocalDate.now().format(DATE_FR)),
                4);

        /* ── the calendar as it ran ── */
        row = section(sheet, row, sectionStyle, "Calendrier");
        row = pair(sheet, row, plainStyle, "Ouverture", fr(session.getStartDate()));
        row = pair(sheet, row, plainStyle, "Fin de réception", fr(session.getReceivingEnd()));
        row = pair(sheet, row, plainStyle, "Fin d'examen", fr(session.getReviewEnd()));
        row = pair(sheet, row, plainStyle, "Fin de correction", fr(session.getCorrectionEnd()));
        row = pair(sheet, row, plainStyle, "Fin de réclamation", fr(session.getReclamationEnd()));
        row = pair(sheet, row, plainStyle, "Expiration des cartes",
                r.cardExpiryDate() == null ? "—" : fr(r.cardExpiryDate()));
        row = pair(sheet, row, plainStyle, "État", r.statusLabelFr());
        row++;

        /* ── the cohort ── */
        row = section(sheet, row, sectionStyle, "Candidatures");
        // Started but never submitted: the gap is part of the record.
        row = figure(sheet, row, plainStyle, numberStyle,
                "Dossiers ouverts", r.started());
        row = figure(sheet, row, plainStyle, numberStyle,
                "Candidatures déposées", r.submitted());
        row = figure(sheet, row, plainStyle, numberStyle,
                "Jamais déposées", r.started() - r.submitted());
        row++;

        row = section(sheet, row, sectionStyle, "Décisions");
        row = figure(sheet, row, plainStyle, numberStyle, "Acceptées", r.accepted());
        row = figure(sheet, row, plainStyle, numberStyle, "Rejetées", r.rejected());
        if (r.inProgress() > 0) {
            row = figure(sheet, row, plainStyle, numberStyle,
                    "En cours d'examen", r.inProgress());
        }
        row = pair(sheet, row, plainStyle, "Taux d'acceptation",
                percent(r.accepted(), r.submitted()));
        row++;

        /* ── the contested ones ──
           The upheld rate measures how often the commission's first decision
           did not hold. It belongs in the record, not only on a screen. */
        if (r.objectionsFiled() > 0) {
            row = section(sheet, row, sectionStyle, "Réclamations");
            row = figure(sheet, row, plainStyle, numberStyle,
                    "Déposées", r.objectionsFiled());
            row = figure(sheet, row, plainStyle, numberStyle,
                    "Décision infirmée", r.objectionsUpheld());
            row = figure(sheet, row, plainStyle, numberStyle,
                    "Rejet confirmé", r.objectionsDismissed());
            row = pair(sheet, row, plainStyle, "Taux d'infirmation",
                    percent(r.objectionsUpheld(), r.objectionsFiled()));
            row++;
        }

        row = section(sheet, row, sectionStyle, "Cartes");
        row = figure(sheet, row, plainStyle, numberStyle, "Éditées", r.cardsIssued());
        if (r.acceptedWithoutCard() > 0) {
            row = figure(sheet, row, plainStyle, numberStyle,
                    "En attente d'édition", r.acceptedWithoutCard());
        }
        row++;

        /* ── by category ── */
        if (!r.byCategory().isEmpty()) {
            row = section(sheet, row, sectionStyle, "Par catégorie");

            Row header = sheet.createRow(row++);
            String[] cols = { "Catégorie", "Déposées", "Acceptées", "Rejetées", "Cartes" };
            for (int i = 0; i < cols.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
            }

            for (SessionResultsService.CategoryTally tally : r.byCategory()) {
                Row line = sheet.createRow(row++);
                write(line, 0, tally.labelFr(), plainStyle);
                number(line, 1, tally.submitted(), numberStyle);
                number(line, 2, tally.accepted(), numberStyle);
                number(line, 3, tally.rejected(), numberStyle);
                number(line, 4, tally.cardsIssued(), numberStyle);
            }
        }

        sheet.setColumnWidth(0, 8000);
        for (int i = 1; i <= 4; i++) {
            sheet.setColumnWidth(i, 3600);
        }
    }

    /* ══ sheet 2 — the cohort ═══════════════════════════════════ */

    private void writeCandidates(Workbook workbook,
                                 Session session,
                                 List<SessionResultsService.CandidateOutcome> candidates,
                                 CellStyle titleStyle, CellStyle noticeStyle,
                                 CellStyle headerStyle, CellStyle plainStyle) {

        Sheet sheet = workbook.createSheet("Candidats");
        int row = 0;

        row = writeTitle(sheet, row, titleStyle, noticeStyle,
                "Session n° %d — candidatures".formatted(session.getId()),
                ("DOCUMENT INTERNE — contient des données personnelles. "
               + "Diffusion restreinte à la HAPA. Édité le %s.")
                        .formatted(LocalDate.now().format(DATE_FR)),
                HEADERS.length);

        int headerRowIndex = row;
        Row header = sheet.createRow(row++);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        for (SessionResultsService.CandidateOutcome c : candidates) {
            Row line = sheet.createRow(row++);
            int col = 0;
            write(line, col++, c.fullName(), plainStyle);
            write(line, col++, c.categoryLabelFr(), plainStyle);
            write(line, col++, c.specialisationFr(), plainStyle);
            write(line, col++, c.institution(), plainStyle);
            // The grouped outcome, not the nine-state name: "UNDER_FINAL_REVIEW"
            // is a distinction for the commission, not for a report.
            write(line, col++, outcomeLabel(c.outcome()), plainStyle);
            write(line, col++, c.submittedAt() == null ? "—"
                    : c.submittedAt().toLocalDate().format(DATE_FR), plainStyle);
            write(line, col++, c.objected() ? "Oui" : "—", plainStyle);
            write(line, col++, c.cardNumber(), plainStyle);
            write(line, col, cardStatusLabel(c.cardStatus()), plainStyle);
        }

        // A filter row so HAPA can sort by outcome or category immediately.
        sheet.setAutoFilter(new CellRangeAddress(
                headerRowIndex, Math.max(headerRowIndex, row - 1), 0, HEADERS.length - 1));
        sheet.createFreezePane(0, headerRowIndex + 1);

        for (int i = 0; i < HEADERS.length; i++) {
            sheet.autoSizeColumn(i);
            // autoSizeColumn is tight to the point of unreadable.
            sheet.setColumnWidth(i, Math.min(sheet.getColumnWidth(i) + 900, 12000));
        }
    }

    /* ══ shared shapes ══════════════════════════════════════════ */

    /** Title, the internal-use notice, then a blank line. */
    private static int writeTitle(Sheet sheet, int row,
                                  CellStyle titleStyle, CellStyle noticeStyle,
                                  String title, String notice, int span) {
        Row titleRow = sheet.createRow(row);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(row, row, 0, span - 1));
        row++;

        Row noticeRow = sheet.createRow(row);
        Cell noticeCell = noticeRow.createCell(0);
        noticeCell.setCellValue(notice);
        noticeCell.setCellStyle(noticeStyle);
        sheet.addMergedRegion(new CellRangeAddress(row, row, 0, span - 1));
        row++;

        return row + 1;   // a blank line
    }

    private static int section(Sheet sheet, int row, CellStyle style, String label) {
        Cell cell = sheet.createRow(row).createCell(0);
        cell.setCellValue(label);
        cell.setCellStyle(style);
        return row + 1;
    }

    private static int pair(Sheet sheet, int row, CellStyle style,
                            String label, String value) {
        Row line = sheet.createRow(row);
        write(line, 0, label, style);
        write(line, 1, value, style);
        return row + 1;
    }

    /**
     * A label and a REAL NUMBER, not a string.
     *
     * Written as numeric so HAPA can total a column or chart it without first
     * converting the cell — a report they cannot compute with is half a
     * report.
     */
    private static int figure(Sheet sheet, int row, CellStyle labelStyle,
                              CellStyle numberStyle, String label, long value) {
        Row line = sheet.createRow(row);
        write(line, 0, label, labelStyle);
        number(line, 1, value, numberStyle);
        return row + 1;
    }

    private static void write(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null || value.isBlank() ? "—" : value);
        cell.setCellStyle(style);
    }

    private static void number(Row row, int column, long value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String fr(LocalDate date) {
        return date == null ? "—" : date.format(DATE_FR);
    }

    private static String percent(long part, long whole) {
        return whole == 0 ? "—" : Math.round((part * 100.0) / whole) + " %";
    }

    private static String outcomeLabel(String outcome) {
        return switch (outcome) {
            case "ACCEPTED" -> "Acceptée";
            case "REJECTED" -> "Rejetée";
            case "PENDING"  -> "En cours d'examen";
            case "DRAFT"    -> "Non déposée";
            default -> outcome;
        };
    }

    private static String cardStatusLabel(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "VALID"     -> "Valide";
            case "SUSPENDED" -> "Suspendue";
            case "REVOKED"   -> "Retirée";
            case "EXPIRED"   -> "Expirée";
            default -> status;
        };
    }

    /* ── styles, matching CardRegistryExporter ── */

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

    /** Plain, but right-aligned — a column of figures should line up. */
    private static CellStyle numberStyle(Workbook workbook) {
        CellStyle style = plainStyle(workbook);
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }
}
