package com.erezer.admin.invoice;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.invoice.ImagePreparer.PreparedImage;
import com.erezer.admin.order.DeliveryPaidBy;
import com.erezer.admin.order.ExchangeItemRepository;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderItem;
import com.erezer.admin.order.OrderItemImage;
import com.erezer.admin.order.OrderRepository;
import com.erezer.admin.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final OrderRepository orders;
    private final StorageService storage;
    private final ImagePreparer imagePreparer;
    private final InvoicePdfRenderer renderer;
    private final AppProperties appProperties;
    private final ExchangeItemRepository exchangeItems;

    InvoiceService(OrderRepository orders, StorageService storage, ImagePreparer imagePreparer,
            InvoicePdfRenderer renderer, AppProperties appProperties, ExchangeItemRepository exchangeItems) {
        this.exchangeItems = exchangeItems;
        this.orders = orders;
        this.storage = storage;
        this.imagePreparer = imagePreparer;
        this.renderer = renderer;
        this.appProperties = appProperties;
    }

    @Transactional(readOnly = true)
    public Invoice render(long orderId) {
        Order order = orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        return new Invoice(order.getOrderNumber(), renderer.render(toData(order)));
    }

    private InvoiceData toData(Order order) {
        List<InvoiceData.Line> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            List<PreparedImage> images = new ArrayList<>();
            for (OrderItemImage image : item.getImages()) {
                load(image).flatMap(imagePreparer::prepare).ifPresent(images::add);
            }
            lines.add(new InvoiceData.Line(item.getPosition() + 1, item.getProductName(), item.getProductCode(),
                    item.getDescription(), item.getColor(), item.getSize(), item.getUnitPrice(), item.getQuantity(),
                    item.getLineTotal(), images));
        }
        // When the order was first accepted; null while it is still only placed.
        java.time.ZonedDateTime acceptedAt = order.getStatusHistory().stream()
                .filter(change -> change.getToStatus() == com.erezer.admin.order.OrderStatus.ACCEPTED)
                .map(change -> change.getChangedAt().atZone(appProperties.zoneId()))
                .min(java.util.Comparator.naturalOrder())
                .orElse(null);
        return new InvoiceData(order.getOrderNumber(), order.getCreatedAt().atZone(appProperties.zoneId()), acceptedAt,
                order.getPaymentMethod().label(), order.getCustomerName(), order.getCustomerPhone(),
                order.getCustomerEmail(), order.getCustomerAddress(), lines, order.getSubtotal(), order.getDiscount(),
                order.getDeliveryCharge(), order.getTotal(), order.getAdvanceAmount(), order.getCourier(),
                order.getTrackingNumber(),
                exchange(order));
    }

    private InvoiceData.Exchange exchange(Order order) {
        if (!order.isExchange()) {
            return null;
        }
        List<InvoiceData.Returned> returned = exchangeItems.findByExchangeOrderIdOrderByReplacementPosition(order.getId())
                .stream()
                .map(e -> {
                    String variant = e.getColor() != null && e.getSize() != null ? e.getColor() + " / " + e.getSize()
                            : e.getColor() != null ? e.getColor() : e.getSize();
                    return new InvoiceData.Returned(e.getProductName() + (variant == null ? "" : " (" + variant + ")"),
                            e.getProductCode(), e.getQuantity(), e.getCredit());
                })
                .toList();
        return new InvoiceData.Exchange(orders.findOriginalNumber(order.getExchangeOfOrderId()),
                order.getExchangeReason().label(), order.getExchangeDeliveryPaidBy() == DeliveryPaidBy.EREZER,
                order.getExchangeCredit(), order.getExchangePriceDifference(), order.isExchangeRefund(), returned);
    }

    /** A missing or unreadable image is skipped rather than failing the whole invoice. */
    private Optional<byte[]> load(OrderItemImage image) {
        String key = image.getFile().getObjectKey();
        try (InputStream in = storage.get(key)) {
            return Optional.of(in.readAllBytes());
        } catch (IOException | RuntimeException ex) {
            log.warn("Could not load image {} for the invoice", key, ex);
            return Optional.empty();
        }
    }

    public record Invoice(String orderNumber, byte[] pdf) {

        public String fileName() {
            return "Invoice_" + orderNumber + ".pdf";
        }
    }
}
