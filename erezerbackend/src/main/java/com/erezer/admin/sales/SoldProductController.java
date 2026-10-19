package com.erezer.admin.sales;

import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.sales.SoldProductService.Filter;
import com.erezer.admin.sales.SoldProductService.SoldRow;
import com.erezer.admin.sales.SoldProductService.Summary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sold products by category; dates default to the current month (Asia/Dhaka). */
@RestController
class SoldProductController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SoldProductService service;
    private final AppProperties appProperties;

    SoldProductController(SoldProductService service, AppProperties appProperties) {
        this.service = service;
        this.appProperties = appProperties;
    }

    @GetMapping("/api/sold-products")
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    PageResponse<SoldRow> rows(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.rows(filter(from, to, category, q), Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/api/sold-products/summary")
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q) {
        return service.summary(filter(from, to, category, q));
    }

    /** Names already used on orders, for the Product name suggestions in Add Order. */
    @GetMapping("/api/sold-products/product-names")
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT', 'EXCHANGE_CREATE', 'SALES_VIEW')")
    List<String> productNames() {
        return service.productNames();
    }

    @GetMapping("/api/sold-products/export.xlsx")
    @PreAuthorize("hasAuthority('SALES_VIEW')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q) {
        Filter filter = filter(from, to, category, q);
        byte[] workbook = workbook(service.summary(filter), service.allRows(filter));
        String fileName = "sold-products-" + filter.from() + "-to-" + filter.to() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(workbook);
    }

    private Filter filter(LocalDate from, LocalDate to, String category, String q) {
        LocalDate today = LocalDate.now(appProperties.zoneId());
        return new Filter(from != null ? from : today.withDayOfMonth(1), to != null ? to : today, category, q);
    }

    private byte[] workbook(Summary summary, List<SoldRow> rows) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.BLACK.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd-mm-yyyy h:mm AM/PM"));

            Sheet byCategory = wb.createSheet("By category");
            head(byCategory, header, new String[] {"Category", "Orders", "Pieces", "Sale (no delivery)", "Delivery charge", "Courier cost"},
                    new int[] {32, 9, 9, 18, 16, 14});
            int r = 1;
            for (var c : summary.categories()) {
                Row row = byCategory.createRow(r++);
                row.createCell(0).setCellValue(c.category());
                row.createCell(1).setCellValue(c.orders());
                row.createCell(2).setCellValue(c.pieces());
                money(row, 3, c.sale(), money);
                money(row, 4, c.deliveryCharge(), money);
                money(row, 5, c.courierCost(), money);
            }

            Sheet detail = wb.createSheet("Orders");
            head(detail, header, new String[] {"Order ID", "Delivered", "Category", "Pieces", "Sale (no delivery)", "Delivery charge", "Courier cost"},
                    new int[] {14, 19, 32, 8, 18, 16, 14});
            r = 1;
            for (SoldRow s : rows) {
                Row row = detail.createRow(r++);
                row.createCell(0).setCellValue(s.orderNumber());
                Cell delivered = row.createCell(1);
                delivered.setCellValue(s.deliveredAt().atZone(appProperties.zoneId()).toLocalDateTime());
                delivered.setCellStyle(date);
                row.createCell(2).setCellValue(s.category());
                row.createCell(3).setCellValue(s.pieces());
                money(row, 4, s.sale(), money);
                money(row, 5, s.deliveryCharge(), money);
                money(row, 6, s.courierCost(), money);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void head(Sheet sheet, CellStyle style, String[] titles, int[] widths) {
        Row head = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            Cell cell = head.createCell(i);
            cell.setCellValue(titles[i]);
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }
}
