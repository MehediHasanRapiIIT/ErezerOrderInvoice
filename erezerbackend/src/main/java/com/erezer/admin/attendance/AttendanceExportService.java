package com.erezer.admin.attendance;

import com.erezer.admin.attendance.AttendanceDtos.DayCell;
import com.erezer.admin.attendance.AttendanceDtos.MonthRow;
import com.erezer.admin.attendance.AttendanceDtos.MonthSheet;
import com.erezer.admin.attendance.RemoteDtos.PersonCard;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

/** The monthly sheet as Excel: one row per employee, one column per day, then the totals. */
@Service
public class AttendanceExportService {

    private static final Map<String, String> CODES = Map.of(
            "PRESENT", "P", "LATE", "L", "EARLY", "E", "LATE_EARLY", "LE", "NO_CHECK_OUT", "NC",
            "ABSENT", "A", "OFF", "Off");
    private static final String[] TOTALS = {"Present", "Late", "Early", "Absent", "Off", "Hours", "Extra hours", "Late fees"};

    public byte[] export(MonthSheet sheet) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = workbook.createSheet(sheet.month());
            Font bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.BLACK.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            Row head = s.createRow(0);
            set(head, 0, "Employee", header);
            s.setColumnWidth(0, 24 * 256);
            int col = 1;
            for (LocalDate date : sheet.dates()) {
                set(head, col, String.valueOf(date.getDayOfMonth()), header);
                s.setColumnWidth(col++, 5 * 256);
            }
            for (String title : TOTALS) {
                set(head, col, title, header);
                s.setColumnWidth(col++, 10 * 256);
            }
            s.createFreezePane(1, 1);

            int r = 1;
            for (MonthRow row : sheet.rows()) {
                Row line = s.createRow(r++);
                line.createCell(0).setCellValue(row.fullName());
                int c = 1;
                for (DayCell day : row.days()) {
                    line.createCell(c++).setCellValue(CODES.getOrDefault(day.status(), ""));
                }
                line.createCell(c++).setCellValue(row.present());
                line.createCell(c++).setCellValue(row.late());
                line.createCell(c++).setCellValue(row.early());
                line.createCell(c++).setCellValue(row.absent());
                line.createCell(c++).setCellValue(row.off());
                line.createCell(c++).setCellValue(Math.round(row.workedMinutes() / 6.0) / 10.0);
                line.createCell(c++).setCellValue(Math.round(row.extraMinutes() / 6.0) / 10.0);
                line.createCell(c).setCellValue(row.lateFees().doubleValue());
            }
            s.createRow(r + 1).createCell(0).setCellValue(
                    "P present · L late · E left early · LE both · NC no check-out · A absent · Off weekly off");
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final String[] REMOTE_TITLES = {"Moderator", "Hours", "Extra hours", "Days worked", "Late days", "Left early", "Not turned off",
            "Late fees"};

    /** Remote attendance for a month: one row per person with the card totals. */
    public byte[] exportRemote(String month, List<PersonCard> rows) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = workbook.createSheet("Remote " + month);
            Font bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.BLACK.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Row head = s.createRow(0);
            for (int c = 0; c < REMOTE_TITLES.length; c++) {
                set(head, c, REMOTE_TITLES[c], header);
                s.setColumnWidth(c, (c == 0 ? 24 : 14) * 256);
            }
            int r = 1;
            for (PersonCard row : rows) {
                Row line = s.createRow(r++);
                line.createCell(0).setCellValue(row.fullName());
                line.createCell(1).setCellValue(Math.round(row.workedMinutes() / 6.0) / 10.0);
                line.createCell(2).setCellValue(Math.round(row.extraMinutes() / 6.0) / 10.0);
                line.createCell(3).setCellValue(row.daysWorked());
                line.createCell(4).setCellValue(row.lateDays());
                line.createCell(5).setCellValue(row.earlyDays());
                line.createCell(6).setCellValue(row.notTurnedOff());
                line.createCell(7).setCellValue(row.lateFees().doubleValue());
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void set(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }
}
