package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.order.OrderQueryService.OrderFilter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Excel export of the order list with the same filters as the list screen. */
@Service
public class OrderExportService {

    /** Guards memory and response size; narrow the filters to export more. */
    static final int MAX_ROWS = 20_000;

    private static final String[] HEADERS = {
            "Order ID", "Date", "Status", "Payment", "Customer", "Phone", "Email", "Address", "Products",
            "Items", "Subtotal", "Discount", "Delivery charge", "Total", "Return delivery charge", "Courier",
            "Tracking number"};
    private static final int[] WIDTHS = {14, 18, 16, 16, 22, 15, 24, 36, 48, 7, 12, 11, 14, 12, 14, 14, 18};

    private final OrderRepository orders;
    private final OrderQueryService queryService;
    private final AppProperties appProperties;

    OrderExportService(OrderRepository orders, OrderQueryService queryService, AppProperties appProperties) {
        this.orders = orders;
        this.queryService = queryService;
        this.appProperties = appProperties;
    }

    @Transactional(readOnly = true)
    public byte[] export(OrderFilter filter, Sort sort) {
        Page<Order> page = orders.findAll(queryService.specification(filter), PageRequest.of(0, MAX_ROWS, sort));
        if (page.getTotalElements() > MAX_ROWS) {
            throw ApiException.badRequest("EXPORT_TOO_LARGE", "More than " + MAX_ROWS
                    + " orders match. Narrow the date range or filters and export again.");
        }

        try (SXSSFWorkbook workbook = new SXSSFWorkbook(200);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Orders");
            Styles styles = new Styles(workbook);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(styles.header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }
            sheet.createFreezePane(1, 1);

            int r = 1;
            for (Order order : page.getContent()) {
                writeRow(sheet.createRow(r++), order, styles);
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

    private void writeRow(Row row, Order o, Styles styles) {
        int c = 0;
        text(row, c++, o.getOrderNumber());
        Cell date = row.createCell(c++);
        date.setCellValue(o.getCreatedAt().atZone(appProperties.zoneId()).toLocalDateTime());
        date.setCellStyle(styles.dateTime);
        text(row, c++, label(o.getStatus()));
        text(row, c++, o.getPaymentMethod().label());
        text(row, c++, o.getCustomerName());
        text(row, c++, o.getCustomerPhone());
        text(row, c++, o.getCustomerEmail());
        text(row, c++, o.getCustomerAddress());
        text(row, c++, products(o.getItems()));
        Cell qty = row.createCell(c++);
        qty.setCellValue(o.getItems().stream().mapToInt(OrderItem::getQuantity).sum());
        money(row, c++, o.getSubtotal(), styles);
        money(row, c++, o.getDiscount(), styles);
        money(row, c++, o.getDeliveryCharge(), styles);
        money(row, c++, o.getTotal(), styles);
        money(row, c++, o.getReturnDeliveryCharge(), styles);
        text(row, c++, o.getCourier());
        text(row, c, o.getTrackingNumber());
    }

    private static String products(List<OrderItem> items) {
        return items.stream()
                .map(i -> i.getProductName() + " (" + i.getProductCode() + ")"
                        + (i.getSize() == null ? "" : " " + i.getSize()) + " ×" + i.getQuantity())
                .collect(Collectors.joining("; "));
    }

    private static String label(OrderStatus status) {
        String text = status.name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static void text(Row row, int column, String value) {
        if (value != null) {
            row.createCell(column).setCellValue(value);
        }
    }

    private static void money(Row row, int column, BigDecimal value, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(styles.money);
    }

    private static final class Styles {

        final CellStyle header;
        final CellStyle money;
        final CellStyle dateTime;

        Styles(SXSSFWorkbook workbook) {
            Font bold = workbook.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.BLACK.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

            dateTime = workbook.createCellStyle();
            dateTime.setDataFormat(workbook.createDataFormat().getFormat("dd-mm-yyyy hh:mm"));
        }
    }
}
