package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.file.FileService;
import com.erezer.admin.order.dto.OrderDtos.Customer;
import com.erezer.admin.order.dto.OrderDtos.Image;
import com.erezer.admin.order.dto.OrderDtos.Item;
import com.erezer.admin.order.dto.OrderDtos.Note;
import com.erezer.admin.order.dto.OrderDtos.OrderDetail;
import com.erezer.admin.order.dto.OrderDtos.OrderStats;
import com.erezer.admin.order.dto.OrderDtos.OrderSummary;
import com.erezer.admin.order.dto.OrderDtos.StatusChange;
import com.erezer.admin.order.dto.OrderDtos.UserRef;
import com.erezer.admin.user.UserDirectory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderNoteRepository notes;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final AppProperties appProperties;
    private final ExchangeItemRepository exchangeItems;
    private final ReturnService returnService;

    OrderQueryService(OrderRepository orders, OrderNoteRepository notes, UserDirectory userDirectory,
            FileService files, AppProperties appProperties, ExchangeItemRepository exchangeItems, ReturnService returnService) {
        this.exchangeItems = exchangeItems;
        this.returnService = returnService;
        this.orders = orders;
        this.notes = notes;
        this.userDirectory = userDirectory;
        this.files = files;
        this.appProperties = appProperties;
    }

    public PageResponse<OrderSummary> search(OrderFilter filter, Pageable pageable) {
        return PageResponse.of(orders.findAll(specification(filter), pageable), OrderQueryService::toSummary);
    }

    /** Ids of every order matching the filter (capped), for "select all matching" in bulk actions. */
    public MatchingIds ids(OrderFilter filter, org.springframework.data.domain.Sort sort, int limit) {
        var page = orders.findAll(specification(filter), org.springframework.data.domain.PageRequest.of(0, limit, sort));
        return new MatchingIds(page.getContent().stream().map(Order::getId).toList(), page.getTotalElements());
    }

    public record MatchingIds(List<Long> ids, long total) {
    }

    /** The filter as a JPA specification; shared by the list and the Excel export. */
    Specification<Order> specification(OrderFilter filter) {
        List<Specification<Order>> specs = Stream.of(
                        OrderSpecifications.statusIn(filter.statuses()),
                        OrderSpecifications.paymentMethod(filter.paymentMethod()),
                        OrderSpecifications.type(filter.type()),
                        OrderSpecifications.balance(filter.balance()),
                        OrderSpecifications.createdFrom(startOfDay(filter.from())),
                        OrderSpecifications.createdBefore(startOfDay(filter.to() == null ? null : filter.to().plusDays(1))),
                        OrderSpecifications.search(filter.query()))
                .filter(Objects::nonNull)
                .toList();
        return Specification.allOf(specs);
    }

    public OrderStats stats() {
        long total = 0;
        long pending = 0;
        long inTransit = 0;
        long delivered = 0;
        for (OrderRepository.StatusCount row : orders.countByStatus()) {
            total += row.getCount();
            if (OrderStatus.PENDING.contains(row.getStatus())) {
                pending += row.getCount();
            } else if (OrderStatus.IN_TRANSIT.contains(row.getStatus())) {
                inTransit += row.getCount();
            } else if (row.getStatus() == OrderStatus.DELIVERED) {
                delivered += row.getCount();
            }
        }
        return new OrderStats(total, pending, inTransit, delivered);
    }

    public OrderDetail detail(long id) {
        Order order = orders.findDetailedById(id).orElseThrow(() -> ApiException.notFound("Order", id));
        var returns = returnService.viewsOf(id);
        List<OrderNote> orderNotes = notes.findByOrderIdOrderByCreatedAtAscIdAsc(id);

        List<Long> userIds = new ArrayList<>();
        userIds.add(order.getCreatedBy());
        order.getStatusHistory().forEach(h -> userIds.add(h.getChangedBy()));
        orderNotes.forEach(n -> userIds.add(n.getCreatedBy()));
        Map<Long, String> names = userDirectory.namesById(userIds);

        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getId(), i.getProductName(), i.getProductCode(), i.getDescription(), i.getColor(),
                        i.getSize(), i.getUnitPrice(), i.getQuantity(), i.getLineTotal(),
                        i.getImages().stream()
                                .map(img -> new Image(img.getFile().getId(), files.url(img.getFile())))
                                .toList(), i.getVariantId()))
                .toList();

        return new OrderDetail(order.getId(), order.getOrderNumber(), order.getStatus(), order.getPaymentMethod(),
                new Customer(order.getCustomerName(), order.getCustomerPhone(), order.getCustomerEmail(),
                        order.getCustomerAddress()),
                items, order.getSubtotal(), order.getDiscount(), order.getDeliveryCharge(), order.getTotal(),
                order.getAdvanceAmount(), order.getDueAmount(), order.getReturnDeliveryCharge(), order.getCourier(), order.getTrackingNumber(),
                userRef(names, order.getCreatedBy()), order.getCreatedAt(), order.getUpdatedAt(), order.getVersion(),
                order.isEditable(), allowedStatuses(order),
                order.getStatusHistory().stream()
                        .map(h -> new StatusChange(h.getFromStatus(), h.getToStatus(), h.getNote(),
                                userRef(names, h.getChangedBy()), h.getChangedAt()))
                        .toList(),
                orderNotes.stream()
                        .map(n -> new Note(n.getId(), n.getBody(), userRef(names, n.getCreatedBy()), n.getCreatedAt()))
                        .toList(),
                order.getOrderType(), exchangeInfo(order),
                orders.findByExchangeOfOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                        .map(o -> new com.erezer.admin.order.dto.OrderDtos.ExchangeRef(o.getId(), o.getOrderNumber(),
                                o.getStatus(), o.getCreatedAt()))
                        .toList(),
                new com.erezer.admin.order.dto.OrderDtos.CourierCost(order.hasCourierCost(), order.getCourierCharge(),
                        order.getReturnDeliveryCharge(), order.getTotalCourierCost(), order.getCourierCharge() == null,
                        order.getStatus() == OrderStatus.RETURNED && order.getDeliveredAt() == null),
                com.erezer.admin.security.CurrentUser.has(com.erezer.admin.security.Permission.ORDER_STATUS_OVERRIDE),
                returns, order.getStatus() == OrderStatus.DELIVERED
                        && returns.stream().anyMatch(r -> r.active() && r.kind() == ReturnKind.AFTER_DELIVERY));
    }

    /** The normal next steps, or every other status for users who may change it freely. */
    private static List<OrderStatus> allowedStatuses(Order order) {
        boolean free = com.erezer.admin.security.CurrentUser.has(com.erezer.admin.security.Permission.ORDER_STATUS_OVERRIDE);
        var statuses = free ? OrderStatus.selectable() : order.getStatus().allowedNext();
        return statuses.stream().filter(s -> s != order.getStatus() && s != OrderStatus.IN_PRODUCTION).sorted().toList();
    }

    private com.erezer.admin.order.dto.OrderDtos.ExchangeInfo exchangeInfo(Order order) {
        if (!order.isExchange()) {
            return null;
        }
        String originalNumber = orders.findOriginalNumber(order.getExchangeOfOrderId());
        return new com.erezer.admin.order.dto.OrderDtos.ExchangeInfo(order.getExchangeOfOrderId(), originalNumber,
                order.getExchangeReason(), order.getExchangeReason().label(), order.getExchangeDeliveryPaidBy(),
                order.getExchangeCredit(), order.getExchangePriceDifference(), order.isExchangeRefund(),
                order.getExchangeRefundAmount(),
                exchangeItems.findByExchangeOrderIdOrderByReplacementPosition(order.getId()).stream()
                        .map(e -> new com.erezer.admin.order.dto.OrderDtos.ReturnedItem(e.getProductName(),
                                e.getProductCode(), e.getColor(), e.getSize(), e.getQuantity(), e.getUnitCredit(),
                                e.getCredit()))
                        .toList());
    }

    private static OrderSummary toSummary(Order o) {
        return new OrderSummary(o.getId(), o.getOrderNumber(), o.getOrderType(), o.getCustomerName(), o.getCustomerPhone(),
                o.getTotal(), o.getAdvanceAmount(), o.getDueAmount(), o.getPaymentMethod(), o.getStatus(), o.getCreatedAt());
    }

    private static UserRef userRef(Map<Long, String> names, Long id) {
        return id == null ? null : new UserRef(id, UserDirectory.nameOf(names, id));
    }

    private Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(appProperties.zoneId()).toInstant();
    }

    /**
     * @param statuses explicit statuses; when empty, all statuses
     * @param from     inclusive business-day start (Asia/Dhaka)
     * @param to       inclusive business-day end (Asia/Dhaka)
     */
    public record OrderFilter(Collection<OrderStatus> statuses, PaymentMethod paymentMethod, LocalDate from,
            LocalDate to, String query, OrderType type, Balance balance) {
    }

    /** {@code ADVANCE}: an advance was paid; {@code DUE}: something is still to be collected. */
    public enum Balance {
        ADVANCE,
        DUE
    }
}
