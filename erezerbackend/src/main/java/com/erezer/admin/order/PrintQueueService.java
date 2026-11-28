package com.erezer.admin.order;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.file.FileService;
import com.erezer.admin.user.UserDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Order to Print": every order not yet shipped, with its products' pictures, names and sizes, so they can be
 * printed. An order leaves when it is shipped (or cancelled); the "printed" tick only says the printing is done.
 * Prices and the customer's details are left out, so people who only print don't see them.
 */
@Service
@Transactional(readOnly = true)
public class PrintQueueService {

    /** Waiting to be shipped. */
    static final Set<OrderStatus> WAITING = EnumSet.copyOf(OrderStatus.PENDING);

    private final OrderRepository orders;
    private final FileService files;
    private final UserDirectory userDirectory;
    private final Clock clock;

    PrintQueueService(OrderRepository orders, FileService files, UserDirectory userDirectory, Clock clock) {
        this.orders = orders;
        this.files = files;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    public record Image(String url, String description) {
    }

    public record Item(String productName, String productCode, String color, String size, int quantity, String description,
            List<Image> images) {
    }

    public record PrintOrder(long id, String orderNumber, OrderType orderType, OrderStatus status, Instant orderedAt,
            boolean urgent, OrderPlatform platform, Instant printedAt, String printedBy, List<Item> items) {
    }

    public record Counts(long total, long printed, long notPrinted) {
    }

    /**
     * @param printed  true: only printed; false: only not printed; null: both
     * @param newest   newest first instead of oldest first
     */
    public PageResponse<PrintOrder> queue(String q, Boolean printed, boolean newest, int page, int size) {
        Sort.Direction direction = newest ? Sort.Direction.DESC : Sort.Direction.ASC;
        Sort sort = Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
        var result = orders.findAll(specification(q, printed), PageRequest.of(page, size, sort));
        List<Long> printers = result.getContent().stream().map(Order::getPrintedBy).filter(Objects::nonNull).toList();
        Map<Long, String> names = userDirectory.namesById(printers);
        return PageResponse.of(result, o -> toView(o, names));
    }

    public Counts counts(String q) {
        long total = orders.count(specification(q, null));
        long printed = orders.count(specification(q, true));
        return new Counts(total, printed, total - printed);
    }

    /** Ticks the order as printed, or takes the tick away. */
    @Transactional
    public PrintOrder markPrinted(long id, boolean printed, long userId) {
        Order order = orders.findById(id).orElseThrow(() -> ApiException.notFound("Order", id));
        if (!WAITING.contains(order.getStatus())) {
            throw ApiException.conflict("NOT_IN_PRINT_QUEUE", "This order is no longer waiting to be printed");
        }
        order.setPrinted(printed ? clock.instant() : null, printed ? userId : null);
        Map<Long, String> names = userDirectory.namesById(printed ? List.of(userId) : List.of());
        return toView(order, names);
    }

    private static Specification<Order> specification(String q, Boolean printed) {
        List<Specification<Order>> specs = Stream.of(
                        OrderSpecifications.statusIn(WAITING),
                        OrderSpecifications.search(q),
                        printed == null ? null : OrderSpecifications.printed(printed))
                .filter(Objects::nonNull)
                .toList();
        return Specification.allOf(specs);
    }

    private PrintOrder toView(Order o, Map<Long, String> names) {
        List<Item> items = o.getItems().stream()
                .map(i -> new Item(i.getProductName(), i.getProductCode(), i.getColor(), i.getSize(), i.getQuantity(),
                        i.getDescription(),
                        i.getImages().stream().map(img -> new Image(files.url(img.getFile()), img.getDescription())).toList()))
                .toList();
        return new PrintOrder(o.getId(), o.getOrderNumber(), o.getOrderType(), o.getStatus(), o.getCreatedAt(), o.isUrgent(),
                o.getPlatform(), o.getPrintedAt(), o.getPrintedBy() == null ? null : UserDirectory.nameOf(names, o.getPrintedBy()),
                items);
    }
}
