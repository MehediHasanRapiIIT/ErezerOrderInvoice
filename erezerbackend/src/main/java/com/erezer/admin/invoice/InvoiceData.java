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
        String courier,
        String trackingNumber) {

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
