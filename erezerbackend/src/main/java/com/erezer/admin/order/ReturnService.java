package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.file.FileService;
import com.erezer.admin.order.dto.OrderDtos.Customer;
import com.erezer.admin.order.dto.OrderDtos.Image;
import com.erezer.admin.order.dto.OrderDtos.ReturnLineRequest;
import com.erezer.admin.order.dto.OrderDtos.ReturnOptions;
import com.erezer.admin.order.dto.OrderDtos.ReturnRequest;
import com.erezer.admin.order.dto.OrderDtos.ReturnUpdateRequest;
import com.erezer.admin.order.dto.OrderDtos.ReturnView;
import com.erezer.admin.order.dto.OrderDtos.ReturnableItem;
import com.erezer.admin.order.dto.OrderDtos.ReturnedItem;
import com.erezer.admin.order.dto.OrderDtos.UserRef;
import com.erezer.admin.stock.StockReason;
import com.erezer.admin.stock.StockService;
import com.erezer.admin.user.UserDirectory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Returns, kept as records like exchanges.
 *
 * <ul>
 *   <li><b>After delivery</b> (the Return page): any products and quantities still with the customer (not already
 *       returned or exchanged). When everything is back, the order becomes Returned; otherwise it stays Delivered
 *       ("partly returned").</li>
 *   <li><b>Refused at the door / whole order</b>: recorded when an order is marked Returned (by staff or by the
 *       courier's update).</li>
 *   <li>Money: the courier always charges Erezer the return fee. When the customer pays it, it is deducted from the
 *       refund (after delivery) or covered by the customer (refused, e.g. out of an advance).</li>
 *   <li>Stock: returned pieces go back on the shelf unless the return says otherwise (defective, damaged).</li>
 * </ul>
 */
@Service
public class ReturnService {

    private final OrderRepository orders;
    private final OrderReturnRepository returns;
    private final OrderNoteRepository notes;
    private final StockService stock;
    private final FileService files;
    private final UserDirectory userDirectory;
    private final JdbcClient jdbc;
    private final Clock clock;

    ReturnService(OrderRepository orders, OrderReturnRepository returns, OrderNoteRepository notes, StockService stock,
            FileService files, UserDirectory userDirectory, JdbcClient jdbc, Clock clock) {
        this.orders = orders;
        this.returns = returns;
        this.notes = notes;
        this.stock = stock;
        this.files = files;
        this.userDirectory = userDirectory;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReturnOptions options(long orderId) {
        Order order = orders.findDetailedById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        requireDelivered(order);
        Map<Long, Integer> returned = returnedQuantities(orderId);
        Map<Long, Integer> exchanged = exchangedQuantities(orderId);
        List<ReturnableItem> items = order.getItems().stream().map(i -> {
            int r = returned.getOrDefault(i.getId(), 0);
            int e = exchanged.getOrDefault(i.getId(), 0);
            return new ReturnableItem(i.getId(), i.getProductName(), i.getProductCode(), i.getColor(), i.getSize(),
                    i.getUnitPrice(), ExchangeService.unitCredit(order, i), i.getQuantity(), r, e,
                    Math.max(i.getQuantity() - r - e, 0),
                    i.getImages().stream().map(img -> new Image(img.getFile().getId(), files.url(img.getFile()))).toList());
        }).toList();
        return new ReturnOptions(order.getId(), order.getOrderNumber(),
                new Customer(order.getCustomerName(), order.getCustomerPhone(), order.getCustomerEmail(), order.getCustomerAddress()),
                order.getDeliveredAt(), order.getDeliveryCharge(), items, viewsOf(orderId));
    }

    /** Products sent back after delivery. Returns the return's id. */
    @Transactional
    public long create(long orderId, ReturnRequest request, long userId) {
        // Locked, so two returns (or a return and an exchange) cannot take the same pieces at once.
        Order order = orders.findForCourierById(orderId).orElseThrow(() -> ApiException.notFound("Order", orderId));
        requireDelivered(order);
        Map<Long, OrderItem> items = order.getItems().stream().collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        Map<Long, Integer> returned = returnedQuantities(orderId);
        Map<Long, Integer> exchanged = exchangedQuantities(orderId);
        Set<Long> seen = new HashSet<>();
        for (ReturnLineRequest line : request.items()) {
            OrderItem item = items.get(line.orderItemId());
            if (item == null) {
                throw ApiException.badRequest("RETURN_ITEM_UNKNOWN", "A product does not belong to order " + order.getOrderNumber());
            }
            if (!seen.add(item.getId())) {
                throw ApiException.badRequest("RETURN_ITEM_DUPLICATE", item.getProductName() + " is listed twice");
            }
            int remaining = item.getQuantity() - returned.getOrDefault(item.getId(), 0) - exchanged.getOrDefault(item.getId(), 0);
            if (line.quantity() > remaining) {
                throw ApiException.conflict("RETURN_QUANTITY", remaining <= 0
                        ? item.getProductName() + " has already been returned or exchanged"
                        : "Only " + remaining + " of " + item.getProductName() + " can still be returned");
            }
        }

        Instant now = clock.instant();
        // Returns never change stock; staff count returned pieces back in on the Stock page if they can be resold.
        OrderReturn ret = new OrderReturn(orderId, ReturnKind.AFTER_DELIVERY, request.reason(), false, blankToNull(request.note()), userId, now);
        BigDecimal value = BigDecimal.ZERO;
        for (ReturnLineRequest line : request.items()) {
            OrderItem item = items.get(line.orderItemId());
            BigDecimal credit = ExchangeService.unitCredit(order, item);
            ret.addItem(item, line.quantity(), credit);
            value = value.add(credit.multiply(BigDecimal.valueOf(line.quantity())));
        }
        ret.settle(request.returnCharge(), request.paidBy(), value, order.getAdvanceAmount(), BigDecimal.ZERO, now);
        returns.saveAndFlush(ret); // the stock sync reads returns with plain SQL

        // Everything back with the customer's money refunded: the order itself is now Returned.
        request.items().forEach(l -> returned.merge(l.orderItemId(), l.quantity(), Integer::sum));
        boolean whole = order.getItems().stream().allMatch(i -> returned.getOrDefault(i.getId(), 0) >= i.getQuantity());
        if (whole) {
            order.setReturnDeliveryCharge(request.returnCharge());
            order.changeStatus(OrderStatus.RETURNED, "All products returned · " + request.reason().label(), userId, now);
        }
        notes.save(new OrderNote(orderId, describe(ret), userId, now));
        stock.syncOrder(order, StockReason.ORDER_RETURNED, userId);
        return ret.getId();
    }

    /**
     * Records the return for an order that has just been marked Returned: the whole parcel when it was never
     * delivered, otherwise whatever is still with the customer. Called by the status change and the courier sync.
     */
    @Transactional
    public void recordWholeReturn(Order order, ReturnReason reason, BigDecimal charge, DeliveryPaidBy paidBy, Long userId) {
        Instant now = clock.instant();
        boolean refused = order.getDeliveredAt() == null;
        ReturnReason why = reason != null ? reason : refused ? ReturnReason.REFUSED : ReturnReason.OTHER;
        DeliveryPaidBy payer = paidBy != null ? paidBy : DeliveryPaidBy.EREZER;
        OrderReturn ret = new OrderReturn(order.getId(), refused ? ReturnKind.REFUSED : ReturnKind.AFTER_DELIVERY, why,
                false, null, userId, now); // returns never change stock
        BigDecimal value = BigDecimal.ZERO;
        if (!refused) {
            Map<Long, Integer> returned = returnedQuantities(order.getId());
            Map<Long, Integer> exchanged = exchangedQuantities(order.getId());
            for (OrderItem item : order.getItems()) {
                int remaining = item.getQuantity() - returned.getOrDefault(item.getId(), 0) - exchanged.getOrDefault(item.getId(), 0);
                if (remaining > 0) {
                    BigDecimal credit = ExchangeService.unitCredit(order, item);
                    ret.addItem(item, remaining, credit);
                    value = value.add(credit.multiply(BigDecimal.valueOf(remaining)));
                }
            }
        }
        ret.settle(charge == null ? BigDecimal.ZERO : charge, payer, value, order.getAdvanceAmount(), refusedDeliveryCost(order), now);
        returns.saveAndFlush(ret); // the stock sync reads returns with plain SQL
    }

    /** The order was moved out of Returned again: its returns no longer stand. */
    @Transactional
    public void cancelActiveReturns(Order order) {
        Instant now = clock.instant();
        returns.findByOrderIdOrderByCreatedAtDescIdDesc(order.getId()).stream()
                .filter(OrderReturn::isActive)
                .forEach(r -> r.cancel(now));
        returns.flush();
    }

    /** Corrects the reason, the return charge or who paid it; the refund is worked out again. */
    @Transactional
    public long update(long returnId, ReturnUpdateRequest request) {
        OrderReturn ret = returns.findById(returnId).orElseThrow(() -> ApiException.notFound("Return", returnId));
        if (!ret.isActive()) {
            throw ApiException.conflict("RETURN_CANCELLED", "This return was cancelled and can no longer be changed");
        }
        Order order = orders.findById(ret.getOrderId()).orElseThrow(() -> ApiException.notFound("Order", ret.getOrderId()));
        Instant now = clock.instant();
        ret.edit(request.reason(), blankToNull(request.note()), now);
        ret.settle(request.returnCharge(), request.paidBy(), ret.getItemsValue(), order.getAdvanceAmount(), refusedDeliveryCost(order), now);
        if (order.getStatus() == OrderStatus.RETURNED) {
            order.setReturnDeliveryCharge(request.returnCharge());
        }
        return ret.getOrderId();
    }

    @Transactional(readOnly = true)
    public List<ReturnView> viewsOf(long orderId) {
        List<OrderReturn> list = returns.findByOrderIdOrderByCreatedAtDescIdDesc(orderId);
        Map<Long, String> names = userDirectory.namesById(list.stream().map(OrderReturn::getCreatedBy).toList());
        return list.stream().map(r -> new ReturnView(r.getId(), r.getKind(), r.getReason(), r.getReason().label(),
                r.getReturnCharge(), r.getPaidBy(), r.getItemsValue(), r.getRefundAmount(), r.isRestock(), r.getNote(),
                r.getCreatedBy() == null ? null : new UserRef(r.getCreatedBy(), UserDirectory.nameOf(names, r.getCreatedBy())),
                r.getCreatedAt(), r.isActive(),
                r.getItems().stream().map(i -> new ReturnedItem(i.getProductName(), i.getProductCode(), i.getColor(),
                        i.getSize(), i.getQuantity(), i.getUnitCredit(), i.getCredit())).toList()))
                .toList();
    }

    /** Pieces of each order line already returned (by returns that still stand). */
    Map<Long, Integer> returnedQuantities(long orderId) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.sql("""
                        select i.order_item_id, sum(i.quantity) from order_return_items i
                        join order_returns r on r.id = i.return_id
                        where r.order_id = :orderId and r.cancelled_at is null
                        group by i.order_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private Map<Long, Integer> exchangedQuantities(long orderId) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.sql("""
                        select ei.original_item_id, sum(ei.quantity) from order_exchange_items ei
                        join orders o on o.id = ei.exchange_order_id
                        where o.exchange_of_order_id = :orderId and o.deleted_at is null and o.status <> 'CANCELLED'
                          and not (o.status = 'RETURNED' and o.delivered_at is null)
                        group by ei.original_item_id
                        """)
                .param("orderId", orderId)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static String describe(OrderReturn r) {
        List<String> parts = new ArrayList<>();
        r.getItems().forEach(i -> parts.add(i.getProductName() + (i.getSize() == null ? "" : " (" + i.getSize() + ")") + " ×" + i.getQuantity()));
        return "Return: " + String.join(", ", parts) + " · " + r.getReason().label()
                + " · return charge " + taka(r.getReturnCharge()) + " paid by "
                + (r.getPaidBy() == DeliveryPaidBy.CUSTOMER ? "customer" : "Erezer")
                + " · refund " + taka(r.getRefundAmount())
                + (r.isRestock() ? " · back in stock" : "");
    }

    /** The outbound part of a refused parcel's courier cost: the delivery charge (the return charge is separate). */
    private static BigDecimal refusedDeliveryCost(Order order) {
        return order.getDeliveryCharge();
    }

    private static String taka(BigDecimal amount) {
        return "৳" + new java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
                .format(amount == null ? BigDecimal.ZERO : amount);
    }

    private static void requireDelivered(Order order) {
        if (order.getStatus() != OrderStatus.DELIVERED || order.getDeliveredAt() == null) {
            throw ApiException.conflict("NOT_RETURNABLE", "Only delivered orders can be returned");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
