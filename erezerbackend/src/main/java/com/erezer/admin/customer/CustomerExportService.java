package com.erezer.admin.customer;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.customer.CustomerDtos.CustomerRow;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

/** Excel export of the customer list with the same filters and sort as the screen. */
@Service
public class CustomerExportService {

    static final int MAX_ROWS = 50_000;

    private static final String[] HEADERS = {"Name", "Phone", "Email", "Address", "Orders", "Delivered", "Returned",
            "Cancelled", "Total paid", "First order", "Last order"};
    private static final int[] WIDTHS = {24, 16, 26, 40, 8, 10, 10, 10, 13, 17, 17};

    private final CustomerQueryService queryService;
    private final AppProperties appProperties;

    CustomerExportService(CustomerQueryService queryService, AppProperties appProperties) {
        this.queryService = queryService;
        this.appProperties = appProperties;
    }

    public byte[] export(CustomerQuery query) {
        if (queryService.count(query) > MAX_ROWS) {
            throw ApiException.badRequest("EXPORT_TOO_LARGE",
                    "More than " + MAX_ROWS + " customers match. Narrow the search and export again.");
        }
        List<CustomerRow> rows = queryService.all(query, MAX_ROWS);

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Customers");
            Font bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.BLACK.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            CellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd-mm-yyyy h:mm AM/PM"));
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@")); // keep phone leading zeros

            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = head.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            sheet.createFreezePane(1, 1);

            int r = 1;
            for (CustomerRow c : rows) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(c.name());
                Cell phone = row.createCell(1);
                phone.setCellValue(c.phone());
                phone.setCellStyle(text);
                if (c.email() != null) {
                    row.createCell(2).setCellValue(c.email());
                }
                row.createCell(3).setCellValue(c.address());
                row.createCell(4).setCellValue(c.orders());
                row.createCell(5).setCellValue(c.delivered());
                row.createCell(6).setCellValue(c.returned());
                row.createCell(7).setCellValue(c.cancelled());
                Cell paid = row.createCell(8);
                paid.setCellValue(c.totalPaid().doubleValue());
                paid.setCellStyle(money);
                Cell first = row.createCell(9);
                first.setCellValue(c.firstOrderAt().atZone(appProperties.zoneId()).toLocalDateTime());
                first.setCellStyle(date);
                Cell last = row.createCell(10);
                last.setCellValue(c.lastOrderAt().atZone(appProperties.zoneId()).toLocalDateTime());
                last.setCellStyle(date);
            }
            if (r > 1) {
                sheet.setAutoFilter(new CellRangeAddress(0, r - 1, 0, HEADERS.length - 1));
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
