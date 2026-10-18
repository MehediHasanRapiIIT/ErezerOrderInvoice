package com.erezer.admin.expense;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.user.UserDirectory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Excel export of the expense list with the same filters, ending in a total row. */
@Service
public class ExpenseExportService {

    static final int MAX_ROWS = 20_000;

    private static final String[] HEADERS = {
            "Date", "Category", "Description", "Amount", "Payment", "Reference", "Notes", "Receipt", "Added by"};
    private static final int[] WIDTHS = {12, 20, 40, 14, 16, 18, 40, 9, 20};

    private final ExpenseRepository expenses;
    private final UserDirectory userDirectory;

    ExpenseExportService(ExpenseRepository expenses, UserDirectory userDirectory) {
        this.expenses = expenses;
        this.userDirectory = userDirectory;
    }

    @Transactional(readOnly = true)
    public byte[] export(ExpenseFilter filter, Sort sort) {
        Page<Expense> page = expenses.findAll(filter.toSpecification(), PageRequest.of(0, MAX_ROWS, sort));
        if (page.getTotalElements() > MAX_ROWS) {
            throw ApiException.badRequest("EXPORT_TOO_LARGE", "More than " + MAX_ROWS
                    + " expenses match. Narrow the date range or filters and export again.");
        }
        List<Expense> rows = page.getContent();
        Map<Long, String> names = userDirectory.namesById(rows.stream().map(Expense::getCreatedBy).toList());

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Expenses");
            CellStyle header = headerStyle(workbook);
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd-mm-yyyy"));

            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            sheet.createFreezePane(0, 1);

            int r = 1;
            BigDecimal total = BigDecimal.ZERO;
            for (Expense e : rows) {
                Row row = sheet.createRow(r++);
                Cell d = row.createCell(0);
                d.setCellValue(e.getExpenseDate());
                d.setCellStyle(date);
                row.createCell(1).setCellValue(e.getCategory().getName());
                row.createCell(2).setCellValue(e.getDescription());
                Cell amount = row.createCell(3);
                amount.setCellValue(e.getAmount().doubleValue());
                amount.setCellStyle(money);
                row.createCell(4).setCellValue(e.getPaymentMethod().label());
                if (e.getReference() != null) {
                    row.createCell(5).setCellValue(e.getReference());
                }
                if (e.getNotes() != null) {
                    row.createCell(6).setCellValue(e.getNotes());
                }
                row.createCell(7).setCellValue(e.getReceipt() == null ? "No" : "Yes");
                if (e.getCreatedBy() != null) {
                    row.createCell(8).setCellValue(UserDirectory.nameOf(names, e.getCreatedBy()));
                }
                total = total.add(e.getAmount());
            }

            Row totalRow = sheet.createRow(r);
            Cell label = totalRow.createCell(2);
            label.setCellValue("Total");
            label.setCellStyle(header);
            Cell sum = totalRow.createCell(3);
            sum.setCellValue(total.doubleValue());
            CellStyle totalStyle = workbook.createCellStyle();
            totalStyle.cloneStyleFrom(header);
            totalStyle.setDataFormat(money.getDataFormat());
            sum.setCellStyle(totalStyle);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static CellStyle headerStyle(SXSSFWorkbook workbook) {
        Font bold = workbook.createFont();
        bold.setBold(true);
        bold.setColor(IndexedColors.WHITE.getIndex());
        CellStyle style = workbook.createCellStyle();
        style.setFont(bold);
        style.setFillForegroundColor(IndexedColors.BLACK.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
