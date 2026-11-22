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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
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
    private final EntityManager entityManager;

    OrderQueryService(OrderRepository orders, OrderNoteRepository notes, UserDirectory userDirectory,
            FileService files, AppProperties appProperties, ExchangeItemRepository exchangeItems, ReturnService returnService,
            EntityManager entityManager) {
        this.entityManager = entityManager;
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
                        OrderSpecifications.createdFrom(dateProperty(filter), startOfDay(filter.from())),
                        OrderSpecifications.createdBefore(dateProperty(filter),
                                startOfDay(filter.to() == null ? null : filter.to().plusDays(1))),
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
        if (order.getAcceptedBy() != null) {
            userIds.add(order.getAcceptedBy());
        }
        order.getStatusHistory().forEach(h -> userIds.add(h.getChangedBy()));
        orderNotes.forEach(n -> userIds.add(n.getCreatedBy()));
        Map<Long, String> names = userDirectory.namesById(userIds);

        List<Item> items = order.getItems().stream()
                .map(i -> new Item(i.getId(), i.getProductName(), i.getProductCode(), i.getDescription(), i.getColor(),
                        i.getSize(), i.getUnitPrice(), i.getQuantity(), i.getLineTotal(),
                        i.getImages().stream()
                                .map(img -> new Image(img.getFile().getId(), files.url(img.getFile()), img.getDescription()))
                                .toList(), i.getVariantId(), i.getStockItemId()))
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
                        && returns.stream().anyMatch(r -> r.active() && r.kind() == ReturnKind.AFTER_DELIVERY),
                order.getDeliveredAt(), userRef(names, order.getAcceptedBy()), order.getPlatform(),
                order.getAcceptedByOther(), order.getPlatformOther());
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
                o.getTotal(), o.getAdvanceAmount(), o.getDueAmount(), o.getPaymentMethod(), o.getStatus(), o.getCreatedAt(),
                o.getEnteredAt());
    }

    private static UserRef userRef(Map<Long, String> names, Long id) {
        return id == null ? null : new UserRef(id, UserDirectory.nameOf(names, id));
    }

    private static String dateProperty(OrderFilter filter) {
        return filter.dateBy() == DateBy.ENTRY ? "enteredAt" : "createdAt";
    }

    /** Totals of every order matching the filter, not just one page. */
    public OrderTotals totals(OrderFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Order> root = query.from(Order.class);
        Predicate where = specification(filter).toPredicate(root, query, cb);
        if (where != null) {
            query.where(where);
        }
        Predicate counted = cb.notEqual(root.get("status"), OrderStatus.CANCELLED);
        query.multiselect(
                cb.count(root),
                cb.sum(cb.<Long>selectCase().when(counted, 0L).otherwise(1L)),
                cb.sum(cb.<BigDecimal>selectCase().when(counted, root.<BigDecimal>get("total")).otherwise(BigDecimal.ZERO)),
                cb.sum(cb.<BigDecimal>selectCase().when(counted, root.<BigDecimal>get("advanceAmount")).otherwise(BigDecimal.ZERO)));
        Tuple row = entityManager.createQuery(query).getSingleResult();
        BigDecimal total = orZero(row.get(2, BigDecimal.class));
        BigDecimal advance = orZero(row.get(3, BigDecimal.class));
        Long cancelled = row.get(1, Long.class);
        return new OrderTotals(row.get(0, Long.class), cancelled == null ? 0 : cancelled, total, advance, total.subtract(advance));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** @param cancelled included in {@code orders} but not in the amounts */
    public record OrderTotals(long orders, long cancelled, BigDecimal total, BigDecimal advance, BigDecimal due) {

        static final OrderTotals NONE = new OrderTotals(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
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
            LocalDate to, String query, OrderType type, Balance balance, DateBy dateBy) {
    }

    /** Which date the from / to filter reads: the order date, or when the order was entered. */
    public enum DateBy {
        ORDER,
        ENTRY
    }

    /** {@code ADVANCE}: an advance was paid; {@code DUE}: something is still to be collected. */
    public enum Balance {
        ADVANCE,
        DUE
    }
}
