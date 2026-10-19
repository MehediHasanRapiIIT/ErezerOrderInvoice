package com.erezer.admin.stock;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.order.ExchangeReason;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderItem;
import com.erezer.admin.order.OrderStatus;
import com.erezer.admin.stock.dto.StockDtos.BulkUpdateItem;
import com.erezer.admin.stock.dto.StockDtos.CreateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.MovementView;
import com.erezer.admin.stock.dto.StockDtos.StockSummary;
import com.erezer.admin.stock.dto.StockDtos.UpdateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.VariantSuggestion;
import com.erezer.admin.stock.dto.StockDtos.VariantView;
import com.erezer.admin.stock.dto.StockDtos;
import com.erezer.admin.user.UserDirectory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock per variant.
 *
 * <p><b>How orders move stock.</b> Instead of adding and subtracting on each event, every change to an order calls
 * {@link #syncOrder}: it works out how many pieces of each variant the order <i>should</i> be holding right now and
 * compares that with what its recorded movements say it holds, then moves only the difference. One rule therefore
 * covers placing, editing, cancelling, returning, deleting and exchanging, and running it twice changes nothing.
 * <ul>
 *   <li>An order holds its items' quantities unless it is cancelled, returned or deleted.</li>
 *   <li>An exchange also gives the returned items back once it is delivered (the rider brings them), except for
 *       defective products, which cannot be sold again.</li>
 *   <li>Orders placed before stock existed ({@code stock_tracked = false}) never move stock.</li>
 * </ul>
 * Taking more than is in stock is refused, so the whole order change is rolled back.
 */
@Service
public class StockService {

    private final StockVariantRepository variants;
    private final StockMovementRepository movements;
    private final JdbcClient jdbc;
    private final UserDirectory userDirectory;
    private final Clock clock;
    private final int lowThreshold;

    StockService(StockVariantRepository variants, StockMovementRepository movements, JdbcClient jdbc,
            UserDirectory userDirectory, Clock clock, @Value("${app.stock.low-threshold:5}") int lowThreshold) {
        this.variants = variants;
        this.movements = movements;
        this.jdbc = jdbc;
        this.userDirectory = userDirectory;
        this.clock = clock;
        this.lowThreshold = lowThreshold;
    }

    // ---- Orders -------------------------------------------------------------------------------------------------

    /** The variants picked on an order; unknown or deleted ones are refused. */
    @Transactional(readOnly = true)
    public Map<Long, StockVariant> requireVariants(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        wanted.remove(null);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<Long, StockVariant> found = variants.findAllById(wanted).stream()
                .collect(Collectors.toMap(StockVariant::getId, Function.identity()));
        if (found.size() != wanted.size()) {
            throw ApiException.badRequest("VARIANT_UNKNOWN", "A variant no longer exists. Choose it again from the list.");
        }
        return found;
    }

    /** Brings the stock held by an order in line with its current state (see the class comment). */
    @Transactional
    public void syncOrder(Order order, StockReason reason, Long userId) {
        if (!order.isStockTracked()) {
            return;
        }
        Map<Long, Integer> desired = new HashMap<>();
        // Returns that still stand and put their pieces back on the shelf. A return without items is the whole order
        // (a refused parcel); an order marked Returned with no return record gives everything back.
        List<Long> restockingItemCounts = jdbc.sql("""
                        select (select count(*) from order_return_items i where i.return_id = r.id)
                        from order_returns r where r.order_id = :id and r.cancelled_at is null and r.restock
                        """)
                .param("id", order.getId()).query(Long.class).list();
        boolean anyReturn = Boolean.TRUE.equals(jdbc.sql(
                        "select exists(select 1 from order_returns where order_id = :id and cancelled_at is null)")
                .param("id", order.getId()).query(Boolean.class).single());
        boolean wholeBack = restockingItemCounts.stream().anyMatch(n -> n == 0)
                || (order.getStatus() == OrderStatus.RETURNED && !anyReturn);
        boolean holds = !order.isDeleted() && order.getStatus() != OrderStatus.CANCELLED && !wholeBack;
        if (holds) {
            for (OrderItem item : order.getItems()) {
                if (item.getVariantId() != null) {
                    desired.merge(item.getVariantId(), item.getQuantity(), Integer::sum);
                }
            }
            jdbc.sql("""
                            select oi.variant_id, sum(ri.quantity) from order_return_items ri
                            join order_returns r on r.id = ri.return_id
                            join order_items oi on oi.id = ri.order_item_id
                            where r.order_id = :id and r.cancelled_at is null and r.restock and oi.variant_id is not null
                            group by oi.variant_id
                            """)
                    .param("id", order.getId())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                    .forEach(e -> desired.merge(e.getKey(), -e.getValue(), Integer::sum));
        }
        if (order.isExchange() && !order.isDeleted() && order.getDeliveredAt() != null
                && order.getExchangeReason() != ExchangeReason.DEFECTIVE) {
            // The old items came back with the rider: they are back on the shelf (a negative holding).
            jdbc.sql("""
                            select oi.variant_id, ei.quantity from order_exchange_items ei
                            join order_items oi on oi.id = ei.original_item_id
                            where ei.exchange_order_id = :id and oi.variant_id is not null
                            """)
                    .param("id", order.getId())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2)))
                    .list()
                    .forEach(e -> desired.merge(e.getKey(), -e.getValue(), Integer::sum));
        }

        Map<Long, Integer> held = new HashMap<>();
        jdbc.sql("select variant_id, -sum(change) from stock_movements where order_id = :id group by variant_id")
                .param("id", order.getId())
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2)))
                .list()
                .forEach(e -> held.put(e.getKey(), e.getValue()));

        // Positive delta = take from the shelf, negative = give back.
        Map<Long, Integer> deltas = new TreeMap<>();
        Set<Long> all = new HashSet<>(desired.keySet());
        all.addAll(held.keySet());
        for (Long variantId : all) {
            int delta = desired.getOrDefault(variantId, 0) - held.getOrDefault(variantId, 0);
            if (delta != 0) {
                deltas.put(variantId, delta);
            }
        }
        if (deltas.isEmpty()) {
            return;
        }
        Map<Long, StockVariant> locked = variants.lockAll(deltas.keySet()).stream()
                .collect(Collectors.toMap(StockVariant::getId, Function.identity()));
        List<String> short_ = new ArrayList<>();
        for (var e : deltas.entrySet()) {
            StockVariant v = locked.get(e.getKey());
            if (v != null && e.getValue() > v.getQuantity()) {
                short_.add(v.getQuantity() == 0 ? v.getName() + " is out of stock"
                        : "only " + v.getQuantity() + " of " + v.getName() + " left");
            }
        }
        if (!short_.isEmpty()) {
            String message = String.join(", ", short_);
            throw ApiException.conflict("OUT_OF_STOCK",
                    "Not enough stock: " + Character.toUpperCase(message.charAt(0)) + message.substring(1) + ".");
        }
        var now = clock.instant();
        for (var e : deltas.entrySet()) {
            StockVariant v = locked.get(e.getKey());
            if (v == null) {
                continue; // the variant was deleted; nothing to give back to
            }
            int after = v.change(-e.getValue());
            movements.save(new StockMovement(v.getId(), -e.getValue(), after, reason, order.getId(),
                    order.getOrderNumber(), userId, now));
        }
    }

    // ---- Managing variants ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<VariantView> list(String query, boolean lowOnly, Pageable pageable) {
        List<Specification<StockVariant>> filters = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            String pattern = "%" + escape(query.trim().toLowerCase(Locale.ROOT)) + "%";
            filters.add((root, q, cb) -> cb.like(cb.lower(root.get("name")), pattern, '\\'));
        }
        if (lowOnly) {
            filters.add((root, q, cb) -> cb.lessThanOrEqualTo(root.get("quantity"), lowThreshold));
        }
        Specification<StockVariant> spec = Specification.allOf(filters);
        Page<StockVariant> page = variants.findAll(spec, pageable);
        return PageResponse.of(page, this::view);
    }

    /** Variants whose name contains the text (those starting with it first), for the order form. */
    @Transactional(readOnly = true)
    public List<VariantSuggestion> suggest(String query, int limit) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return jdbc.sql("""
                        select id, name, quantity from stock_variants
                        where deleted_at is null and lower(name) like :pattern escape '\\'
                        order by (lower(name) like :prefix escape '\\') desc, lower(name)
                        limit :limit
                        """)
                .param("pattern", "%" + escape(term) + "%")
                .param("prefix", escape(term) + "%")
                .param("limit", limit)
                .query((rs, i) -> new VariantSuggestion(rs.getLong(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    @Transactional(readOnly = true)
    public StockSummary summary() {
        return jdbc.sql("""
                        select count(*), coalesce(sum(quantity), 0), count(*) filter (where quantity <= :low)
                        from stock_variants where deleted_at is null
                        """)
                .param("low", lowThreshold)
                .query((rs, i) -> new StockSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), lowThreshold))
                .single();
    }

    @Transactional
    public VariantView create(CreateVariantRequest request, long userId) {
        return createAll(List.of(request), userId).getFirst();
    }

    /** Adds several variants at once; nothing is saved if any name is missing, repeated or already used. */
    @Transactional
    public List<VariantView> createAll(List<CreateVariantRequest> requests, long userId) {
        Map<String, CreateVariantRequest> byName = new LinkedHashMap<>();
        List<String> repeated = new ArrayList<>();
        for (CreateVariantRequest r : requests) {
            String name = cleanName(r.name());
            if (byName.putIfAbsent(name.toLowerCase(Locale.ROOT), r) != null) {
                repeated.add(name);
            }
        }
        if (!repeated.isEmpty()) {
            throw ApiException.badRequest("VARIANT_REPEATED", "Listed more than once: " + String.join(", ", repeated));
        }
        List<String> taken = variants.takenNames(byName.keySet());
        if (!taken.isEmpty()) {
            List<String> names = byName.entrySet().stream().filter(e -> taken.contains(e.getKey()))
                    .map(e -> cleanName(e.getValue().name())).toList();
            throw ApiException.conflict("VARIANT_EXISTS", "Already in stock: " + String.join(", ", names));
        }
        var now = clock.instant();
        List<VariantView> result = new ArrayList<>();
        for (CreateVariantRequest r : byName.values()) {
            StockVariant v = variants.save(new StockVariant(cleanName(r.name()), r.quantity()));
            if (r.quantity() > 0) {
                movements.save(new StockMovement(v.getId(), r.quantity(), r.quantity(), StockReason.ADDED, null, null, userId, now));
            }
            result.add(view(v));
        }
        return result;
    }

    @Transactional
    public VariantView update(long id, UpdateVariantRequest request, long userId) {
        StockVariant v = variants.lockAll(List.of(id)).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Variant", id));
        if (request.version() != null && request.version() != v.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This variant was changed by someone else. Reload and try again.");
        }
        String name = cleanName(request.name());
        if (!name.equals(v.getName())) {
            if (variants.nameTaken(name, id)) {
                throw ApiException.conflict("VARIANT_EXISTS", "Already in stock: " + name);
            }
            v.rename(name);
        }
        adjust(v, request.quantity() - v.getQuantity(), blankToNull(request.note()), userId);
        v.touch(clock.instant());
        return view(v);
    }

    /** Sets, adds or removes quantities of several variants at once; nothing changes if one would go below zero. */
    @Transactional
    public List<VariantView> updateAll(List<BulkUpdateItem> items, String note, long userId) {
        Map<Long, BulkUpdateItem> byId = new LinkedHashMap<>();
        for (BulkUpdateItem item : items) {
            if (byId.put(item.id(), item) != null) {
                throw ApiException.badRequest("VARIANT_REPEATED", "A variant is listed more than once");
            }
        }
        Map<Long, StockVariant> locked = variants.lockAll(byId.keySet()).stream()
                .collect(Collectors.toMap(StockVariant::getId, Function.identity()));
        if (locked.size() != byId.size()) {
            throw ApiException.badRequest("VARIANT_UNKNOWN", "A variant no longer exists. Reload the list.");
        }
        List<String> negative = new ArrayList<>();
        Map<Long, Integer> deltas = new LinkedHashMap<>();
        for (BulkUpdateItem item : byId.values()) {
            StockVariant v = locked.get(item.id());
            int target = switch (item.mode()) {
                case SET -> item.amount();
                case ADD -> v.getQuantity() + item.amount();
                case REMOVE -> v.getQuantity() - item.amount();
            };
            if (target < 0) {
                negative.add(v.getName() + " has only " + v.getQuantity());
            } else if (target > StockDtos.MAX_QUANTITY) {
                negative.add(v.getName() + " would exceed " + StockDtos.MAX_QUANTITY);
            }
            deltas.put(v.getId(), target - v.getQuantity());
        }
        if (!negative.isEmpty()) {
            throw ApiException.badRequest("NOT_ENOUGH_STOCK", "Cannot remove that many: " + String.join(", ", negative));
        }
        String cleanNote = blankToNull(note);
        List<VariantView> result = new ArrayList<>();
        for (var e : deltas.entrySet()) {
            StockVariant v = locked.get(e.getKey());
            adjust(v, e.getValue(), cleanNote, userId);
            if (e.getValue() != 0) {
                v.touch(clock.instant());
            }
            result.add(view(v));
        }
        return result;
    }

    /** Removes a variant from the list. Orders keep its name; its history goes with it. */
    @Transactional
    public void delete(long id) {
        StockVariant v = variants.findById(id).orElseThrow(() -> ApiException.notFound("Variant", id));
        v.markDeleted(clock.instant());
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> history(long variantId, Pageable pageable) {
        variants.findById(variantId).orElseThrow(() -> ApiException.notFound("Variant", variantId));
        Page<StockMovement> page = movements.findByVariantIdOrderByCreatedAtDescIdDesc(variantId, pageable);
        Map<Long, String> names = userDirectory.namesById(page.getContent().stream().map(StockMovement::getCreatedBy).toList());
        Set<Long> orderIds = page.getContent().stream().map(StockMovement::getOrderId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> numbers = new HashMap<>();
        if (!orderIds.isEmpty()) {
            jdbc.sql("select id, order_number from orders where id in (:ids)").param("ids", orderIds)
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2))).list()
                    .forEach(e -> numbers.put(e.getKey(), e.getValue()));
        }
        return PageResponse.of(page, m -> new MovementView(m.getId(), m.getChange(), m.getQuantityAfter(),
                m.getReason().name(), m.getReason().label(), m.getOrderId(),
                m.getOrderId() == null ? null : numbers.get(m.getOrderId()),
                m.getOrderId() == null ? m.getNote() : null,
                m.getCreatedBy() == null ? "System" : UserDirectory.nameOf(names, m.getCreatedBy()), m.getCreatedAt()));
    }

    private void adjust(StockVariant v, int delta, String note, long userId) {
        if (delta == 0) {
            return;
        }
        int after = v.change(delta);
        movements.save(new StockMovement(v.getId(), delta, after, StockReason.ADJUSTED, null, note, userId, clock.instant()));
    }

    private VariantView view(StockVariant v) {
        return new VariantView(v.getId(), v.getName(), v.getQuantity(), v.getQuantity() <= lowThreshold, v.getUpdatedAt(),
                v.getVersion());
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 80) {
            throw ApiException.badRequest("INVALID_VARIANT_NAME", "A variant name must be 1–80 characters");
        }
        return clean;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
