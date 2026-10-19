package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.customer.PhoneKeys;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.order.dto.OrderDtos.Customer;
import com.erezer.admin.order.dto.OrderDtos.ExchangeCandidate;
import com.erezer.admin.order.dto.OrderDtos.ExchangeLineRequest;
import com.erezer.admin.order.dto.OrderDtos.ExchangeOptions;
import com.erezer.admin.order.dto.OrderDtos.ExchangeRef;
import com.erezer.admin.order.dto.OrderDtos.ExchangeRequest;
import com.erezer.admin.order.dto.OrderDtos.ExchangeableItem;
import com.erezer.admin.order.dto.OrderDtos.Image;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product exchanges. An exchange is a new order (type EXCHANGE) for the same customer that sends replacement items
 * and takes the originals back, so the original order, its invoice and its dashboard figures never change.
 *
 * <p>Rules: only delivered orders, within {@value #TIME_LIMIT_DAYS} days of delivery (admins may override); any
 * subset of items and quantities; a unit can be exchanged only once (a cancelled or refused exchange frees it again).
 */
@Service
public class ExchangeService {

    static final int TIME_LIMIT_DAYS = 3;
    private static final int MAX_CANDIDATES = 20;

    private final OrderRepository orders;
    private final ExchangeItemRepository exchangeItems;
    private final OrderNoteRepository notes;
    private final OrderService orderService;
    private final FileService files;
    private final JdbcClient jdbc;
    private final AppProperties appProperties;
    private final Clock clock;
    private final com.erezer.admin.stock.StockService stock;

    ExchangeService(OrderRepository orders, ExchangeItemRepository exchangeItems, OrderNoteRepository notes,
            OrderService orderService, FileService files, JdbcClient jdbc, AppProperties appProperties, Clock clock,
            com.erezer.admin.stock.StockService stock) {
        this.stock = stock;
        this.orders = orders;
        this.exchangeItems = exchangeItems;
        this.notes = notes;
        this.orderService = orderService;
        this.files = files;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    /** Delivered orders matching an order number or a customer phone, newest delivery first. */
    @Transactional(readOnly = true)
    public List<ExchangeCandidate> lookup(String query) {
        String term = query == null ? "" : query.trim();
        if (term.length() < 3) {
            return List.of();
        }
        String phoneKey = PhoneKeys.normalize(term);
        return orders.findExchangeCandidates(term.toUpperCase(Locale.ROOT), phoneKey.length() >= 6 ? phoneKey : "-",
                        org.springframework.data.domain.Limit.of(MAX_CANDIDATES)).stream()
                .map(o -> new ExchangeCandidate(o.getId(), o.getOrderNumber(), o.getCustomerName(), o.getCustomerPhone(),
                        o.getTotal(), o.getDeliveredAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExchangeOptions options(long orderId, boolean admin) {
        Order order = orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        requireDelivered(order);
        Map<Long, Integer> exchanged = exchangedQuantities(orderId);
        List<ExchangeableItem> items = order.getItems().stream()
                .map(i -> {
                    int already = exchanged.getOrDefault(i.getId(), 0);
                    return new ExchangeableItem(i.getId(), i.getProductName(), i.getProductCode(), i.getDescription(),
                            i.getColor(), i.getSize(), i.getUnitPrice(), unitCredit(order, i), i.getQuantity(), already,
                            Math.max(i.getQuantity() - already, 0),
                            i.getImages().stream().map(img -> new Image(img.getFile().getId(), files.url(img.getFile()))).toList(),
                            i.getVariantId());
                })
                .toList();
        return new ExchangeOptions(order.getId(), order.getOrderNumber(),
                new Customer(order.getCustomerName(), order.getCustomerPhone(), order.getCustomerEmail(),
                        order.getCustomerAddress()),
                order.getDeliveredAt(), null, true, admin, 0,
                order.getDeliveryCharge(), items, exchangesOf(orderId));
    }

    /** Creates the exchange order; returns its id. */
    @Transactional
    public long create(long orderId, ExchangeRequest request, long userId, boolean admin) {
        // Locked, so two people exchanging the same order at once cannot both take the last unit.
        Order original = orders.findForCourierById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        requireDelivered(original);
        Instant now = clock.instant();

        Map<Long, OrderItem> originalItems = original.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, Integer> exchanged = exchangedQuantities(orderId);
        Set<Long> seen = new HashSet<>();
        for (ExchangeLineRequest line : request.items()) {
            OrderItem item = originalItems.get(line.originalItemId());
            if (item == null) {
                throw ApiException.badRequest("EXCHANGE_ITEM_UNKNOWN", "An item does not belong to order " + original.getOrderNumber());
            }
            if (!seen.add(item.getId())) {
                throw ApiException.badRequest("EXCHANGE_ITEM_DUPLICATE", item.getProductName() + " is listed twice");
            }
            int remaining = item.getQuantity() - exchanged.getOrDefault(item.getId(), 0);
            if (line.quantity() > remaining) {
                throw ApiException.conflict("EXCHANGE_QUANTITY",
                        remaining <= 0 ? item.getProductName() + " has already been exchanged or returned"
                                : "Only " + remaining + " of " + item.getProductName() + " can still be exchanged");
            }
        }

        BigDecimal credit = BigDecimal.ZERO;
        List<String> returned = new ArrayList<>();
        for (ExchangeLineRequest line : request.items()) {
            OrderItem item = originalItems.get(line.originalItemId());
            credit = credit.add(unitCredit(original, item).multiply(BigDecimal.valueOf(line.quantity())));
            returned.add(describe(item, line.quantity()));
        }
        String summary = String.join(", ", returned);
        if (summary.length() > 500) {
            summary = summary.substring(0, 499) + "…";
        }

        String number = orderService.resolveOrderNumber(null, 0L);
        Order exchange = Order.exchange(number, original, credit, request.refundChosen(), request.deliveryPaidBy(),
                request.reason(), summary, request.deliveryCharge(), now, userId);

        List<Long> imageIds = request.items().stream()
                .flatMap(l -> l.replacement().imageIds() == null ? Stream.<Long>empty() : l.replacement().imageIds().stream())
                .toList();
        Map<Long, StoredFile> images = imageIds.isEmpty() ? Map.of() : files.requireImages(imageIds);
        var variants = stock.requireVariants(request.items().stream().map(l -> l.replacement().variantId()).toList());
        // Each replacement ships in the same quantity as the units taken back.
        exchange.replaceItems(request.items().stream()
                .map(l -> OrderService.toItem(withQuantity(l), images, variants))
                .toList());
        long exchangeId = orders.save(exchange).getId();

        for (int position = 0; position < request.items().size(); position++) {
            ExchangeLineRequest line = request.items().get(position);
            OrderItem item = originalItems.get(line.originalItemId());
            exchangeItems.save(new ExchangeItem(exchangeId, item, position, line.quantity(), unitCredit(original, item)));
        }

        String note = blankToNull(request.note());
        notes.save(new OrderNote(orderId, "Exchange " + number + " created: " + summary + " · " + request.reason().label()
                + " · delivery paid by " + (request.deliveryPaidBy() == DeliveryPaidBy.EREZER ? "Erezer" : "customer")
                + (note == null ? "" : " · " + note), userId, now));
        if (note != null) {
            notes.save(new OrderNote(exchangeId, note, userId, now));
        }
        stock.syncOrder(exchange, com.erezer.admin.stock.StockReason.EXCHANGE, userId);
        return exchangeId;
    }

    /** Exchanges made for an order (newest first), for the order page. */
    @Transactional(readOnly = true)
    public List<ExchangeRef> exchangesOf(long orderId) {
        return orders.findByExchangeOfOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(o -> new ExchangeRef(o.getId(), o.getOrderNumber(), o.getStatus(), o.getCreatedAt()))
                .toList();
    }

    /**
     * Units no longer with the customer per original item: taken back by exchanges that still stand (a cancelled,
     * deleted or refused exchange frees its units again) or by returns.
     */
    private Map<Long, Integer> exchangedQuantities(long orderId) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.sql("""
                        select i.order_item_id, sum(i.quantity) from order_return_items i
                        join order_returns r on r.id = i.return_id
                        where r.order_id = :orderId and r.cancelled_at is null
                        group by i.order_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                .forEach(e -> result.merge(e.getKey(), e.getValue(), Integer::sum));
        jdbc.sql("""
                        select ei.original_item_id as item_id, sum(ei.quantity) as qty
                        from order_exchange_items ei
                        join orders o on o.id = ei.exchange_order_id
                        where o.exchange_of_order_id = :orderId and o.deleted_at is null and o.status <> 'CANCELLED'
                          and not (o.status = 'RETURNED' and o.delivered_at is null)
                        group by ei.original_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, i) -> Map.entry(rs.getLong("item_id"), rs.getInt("qty")))
                .list()
                .forEach(e -> result.merge(e.getKey(), e.getValue(), Integer::sum));
        return result;
    }

    /** End of the {@value #TIME_LIMIT_DAYS}rd business day after delivery (Asia/Dhaka). */
    Instant deadline(Order order) {
        var zone = appProperties.zoneId();
        return order.getDeliveredAt().atZone(zone).toLocalDate().plusDays(TIME_LIMIT_DAYS + 1L).atStartOfDay(zone)
                .toInstant().minusMillis(1);
    }

    /** What the customer actually paid per unit: the price less its share of the order discount. */
    static BigDecimal unitCredit(Order order, OrderItem item) {
        if (order.getDiscount().signum() == 0 || order.getSubtotal().signum() == 0) {
            return item.getUnitPrice();
        }
        BigDecimal share = order.getSubtotal().subtract(order.getDiscount())
                .divide(order.getSubtotal(), 10, RoundingMode.HALF_UP);
        return item.getUnitPrice().multiply(share).setScale(2, RoundingMode.HALF_UP);
    }

    private static void requireDelivered(Order order) {
        if (order.getStatus() != OrderStatus.DELIVERED || order.getDeliveredAt() == null) {
            throw ApiException.conflict("NOT_EXCHANGEABLE", "Only delivered orders can be exchanged");
        }
    }

    private static com.erezer.admin.order.dto.OrderDtos.OrderItemRequest withQuantity(ExchangeLineRequest line) {
        var r = line.replacement();
        return new com.erezer.admin.order.dto.OrderDtos.OrderItemRequest(r.productName(), r.productCode(),
                r.description(), r.color(), r.size(), r.unitPrice(), line.quantity(), r.imageIds(), r.variantId());
    }

    private static String describe(OrderItem item, int quantity) {
        String color = item.getColor();
        String size = item.getSize();
        String variant = color != null && size != null ? color + " / " + size : color != null ? color : size;
        return item.getProductName() + (variant == null ? "" : " (" + variant + ")") + " ×" + quantity;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
