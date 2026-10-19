package com.erezer.admin.invoice;

import com.erezer.admin.invoice.ImagePreparer.PreparedImage;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

/** Everything printed on an invoice, already resolved (no lazy entities, images decoded). */
record InvoiceData(
        String orderNumber,
        ZonedDateTime orderedAt,
        String paymentMethod,
        String customerName,
        String customerPhone,
        String customerEmail,
        String customerAddress,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal deliveryCharge,
        BigDecimal total,
        BigDecimal advance,
        String courier,
        String trackingNumber,
        Exchange exchange) {

    /** Present on exchange invoices: what the exchange replaces and how the money works out. */
    record Exchange(
            String ofOrderNumber,
            String reason,
            boolean paidByErezer,
            BigDecimal credit,
            BigDecimal priceDifference,
            boolean refund,
            List<Returned> returned) {
    }

    /** "Regular Fit T-Shirt (M)", quantity and the credit given for it. */
    record Returned(String description, String productCode, int quantity, BigDecimal credit) {
    }

    record Line(
            int number,
            String productName,
            String productCode,
            String description,
            String color,
            String size,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            List<PreparedImage> images) {

        String variant() {
            if (color != null && size != null) {
                return color + " / " + size;
            }
            return color != null ? color : size;
        }
    }
}
