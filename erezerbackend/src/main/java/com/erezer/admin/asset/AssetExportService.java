package com.erezer.admin.asset;

import com.erezer.admin.common.error.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Excel export of the asset list with the same filters, ending in a total row. */
@Service
public class AssetExportService {

    static final int MAX_ROWS = 20_000;

    private static final String[] HEADERS = {
            "Purchased", "Name", "Category", "Qty", "Unit cost", "Total cost", "Status", "Location", "Serial / model",
            "Warranty until", "Bought from", "Payment", "Reference", "Repairs spent", "Sold / disposed on", "Sale price",
            "Notes"};
    private static final int[] WIDTHS = {12, 32, 18, 7, 13, 14, 14, 16, 20, 14, 20, 14, 18, 14, 16, 12, 40};

    private final AssetRepository assets;
    private final AssetEventRepository events;

    AssetExportService(AssetRepository assets, AssetEventRepository events) {
        this.assets = assets;
        this.events = events;
    }

    @Transactional(readOnly = true)
    /** @param costs include amounts (otherwise those columns stay empty) */
    public byte[] export(AssetFilter filter, Sort sort, boolean costs) {
        Page<Asset> page = assets.findAll(filter.toSpecification(), PageRequest.of(0, MAX_ROWS, sort));
        if (page.getTotalElements() > MAX_ROWS) {
            throw ApiException.badRequest("EXPORT_TOO_LARGE", "More than " + MAX_ROWS
                    + " assets match. Narrow the filters and export again.");
        }
        List<Asset> rows = page.getContent();
        Map<Long, BigDecimal> repairs = new java.util.HashMap<>();
        if (!rows.isEmpty()) {
            for (Object[] r : events.repairTotals(rows.stream().map(Asset::getId).toList())) {
                repairs.put((Long) r[0], (BigDecimal) r[1]);
            }
        }

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Assets");
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
            long pieces = 0;
            for (Asset a : rows) {
                Row row = sheet.createRow(r++);
                dateCell(row, 0, a.getPurchaseDate(), date);
                row.createCell(1).setCellValue(a.getName());
                row.createCell(2).setCellValue(a.getCategory().getName());
                row.createCell(3).setCellValue(a.getQuantity());
                moneyCell(row, 4, costs ? a.getUnitCost() : null, money);
                moneyCell(row, 5, costs ? a.getTotalCost() : null, money);
                row.createCell(6).setCellValue(a.getStatus().label());
                text(row, 7, a.getLocation());
                text(row, 8, a.getSerialNumber());
                dateCell(row, 9, a.getWarrantyUntil(), date);
                text(row, 10, a.getSupplier());
                text(row, 11, a.getPaymentMethod() == null ? null : a.getPaymentMethod().label());
                text(row, 12, a.getReference());
                moneyCell(row, 13, costs ? repairs.getOrDefault(a.getId(), BigDecimal.ZERO) : null, money);
                dateCell(row, 14, a.getDisposedOn(), date);
                moneyCell(row, 15, costs ? a.getSalePrice() : null, money);
                text(row, 16, a.getNotes());
                total = total.add(a.getTotalCost());
                pieces += a.getQuantity();
            }

            Row totalRow = sheet.createRow(r);
            Cell label = totalRow.createCell(2);
            label.setCellValue("Total");
            label.setCellStyle(header);
            Cell qty = totalRow.createCell(3);
            qty.setCellValue(pieces);
            qty.setCellStyle(header);
            Cell sum = totalRow.createCell(5);
            if (costs) {
                sum.setCellValue(total.doubleValue());
            }
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

    private static void text(Row row, int col, String value) {
        if (value != null) {
            row.createCell(col).setCellValue(value);
        }
    }

    private static void moneyCell(Row row, int col, BigDecimal value, CellStyle style) {
        if (value != null) {
            Cell cell = row.createCell(col);
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }
    }

    private static void dateCell(Row row, int col, LocalDate value, CellStyle style) {
        if (value != null) {
            Cell cell = row.createCell(col);
            cell.setCellValue(value);
            cell.setCellStyle(style);
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
