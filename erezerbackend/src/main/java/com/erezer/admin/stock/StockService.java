package com.erezer.admin.stock;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.order.ExchangeReason;
import com.erezer.admin.order.Order;
import com.erezer.admin.order.OrderItem;
import com.erezer.admin.order.OrderStatus;
import com.erezer.admin.stock.dto.StockDtos;
import com.erezer.admin.stock.dto.StockDtos.BulkUpdateItem;
import com.erezer.admin.stock.dto.StockDtos.CreateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.ItemView;
import com.erezer.admin.stock.dto.StockDtos.MergeRequest;
import com.erezer.admin.stock.dto.StockDtos.MovementView;
import com.erezer.admin.stock.dto.StockDtos.SizeQuantity;
import com.erezer.admin.stock.dto.StockDtos.SizeRequest;
import com.erezer.admin.stock.dto.StockDtos.SizeView;
import com.erezer.admin.stock.dto.StockDtos.StockSummary;
import com.erezer.admin.stock.dto.StockDtos.UpdateVariantRequest;
import com.erezer.admin.stock.dto.StockDtos.VariantSuggestion;
import com.erezer.admin.stock.dto.StockDtos.VariantView;
import com.erezer.admin.user.UserDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock per variant and size. A variant ("Black") carries some of the admin's sizes; each variant + size is a
 * <i>stock item</i> with its own count. The variant's {@code quantity} is the total across its sizes.
 *
 * <p><b>How orders move stock.</b> Instead of adding and subtracting on each event, every change to an order calls
 * {@link #syncOrder}: it works out how many pieces of each stock item the order <i>should</i> be holding right now and
 * compares that with what its recorded movements say it holds, then moves only the difference. One rule therefore
 * covers placing, editing, cancelling, returning, deleting and exchanging, and running it twice changes nothing.
 * <ul>
 *   <li>An order holds its items' quantities unless it is cancelled or deleted.</li>
 *   <li>Products that come back from a customer never go back into stock by themselves: returns (refused parcels
 *       and returns after delivery) and the old items of an exchange. Staff add resellable pieces back on the Stock
 *       page. So a cancelled or deleted order still holds the pieces its returns took back.</li>
 *   <li>History from before that rule stays as it was: returns recorded with {@code restock}, and exchanges
 *       delivered with {@code exchange_restocked}, count as given back.</li>
 *   <li>Orders placed before stock existed ({@code stock_tracked = false}) never move stock.</li>
 * </ul>
 * Taking more than is in stock is refused, so the whole order change is rolled back.
 *
 * <p>Quantities only ever change through one guarded SQL update ({@link #apply}), so two orders cannot both take the
 * last piece.
 */
@Service
public class StockService {

    /** Sizes shown on a variant: still carried, and not an empty "No size". */
    private static final String SHOWN = "i.active and not (s.legacy and i.quantity = 0)";
    private static final String NO_SIZE = "No size";

    private final StockVariantRepository variants;
    private final StockMovementRepository movements;
    private final SizeRepository sizes;
    private final JdbcClient jdbc;
    private final UserDirectory userDirectory;
    private final Clock clock;
    private final int lowThreshold;

    StockService(StockVariantRepository variants, StockMovementRepository movements, SizeRepository sizes,
            JdbcClient jdbc, UserDirectory userDirectory, Clock clock,
            @Value("${app.stock.low-threshold:5}") int lowThreshold) {
        this.variants = variants;
        this.movements = movements;
        this.sizes = sizes;
        this.jdbc = jdbc;
        this.userDirectory = userDirectory;
        this.clock = clock;
        this.lowThreshold = lowThreshold;
    }

    // ---- Orders -------------------------------------------------------------------------------------------------

    /** The variant and size picked for an order item. {@code size} is null for "No size". */
    public record StockPick(long variantId, String variantName, long stockItemId, String size) {
    }

    /**
     * Resolves an order item's variant and size. Without a size, a variant that has exactly one size uses it;
     * otherwise the size must be chosen. Returns null when no variant is picked (the item is not tracked).
     */
    @Transactional(readOnly = true)
    public StockPick pick(Long variantId, Long stockItemId) {
        if (variantId == null) {
            return null;
        }
        StockVariant variant = variants.findById(variantId)
                .orElseThrow(() -> ApiException.badRequest("VARIANT_UNKNOWN", "A variant no longer exists. Choose it again from the list."));
        record Row(long id, String size, boolean legacy) {
        }
        List<Row> rows;
        if (stockItemId != null) {
            // Any size of the variant, even one taken off since: an edited order keeps what it had.
            rows = jdbc.sql("""
                            select i.id, s.name, s.legacy from stock_items i join sizes s on s.id = i.size_id
                            where i.id = :item and i.variant_id = :variant
                            """)
                    .param("item", stockItemId).param("variant", variantId)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3))).list();
            if (rows.isEmpty()) {
                throw ApiException.badRequest("SIZE_UNKNOWN", "That size of " + variant.getName() + " no longer exists. Choose it again.");
            }
        } else {
            List<Row> active = jdbc.sql("select i.id, s.name, s.legacy from stock_items i join sizes s on s.id = i.size_id "
                            + "where i.variant_id = :variant and i.active order by s.position, s.id")
                    .param("variant", variantId)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3))).list();
            // Prefer the sizes shown on the Stock page; an empty "No size" counts only when it is all there is.
            List<Row> shown = jdbc.sql("select i.id, s.name, s.legacy from stock_items i join sizes s on s.id = i.size_id "
                            + "where i.variant_id = :variant and " + SHOWN + " order by s.position, s.id")
                    .param("variant", variantId)
                    .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getBoolean(3))).list();
            rows = shown.isEmpty() ? active : shown;
            if (rows.isEmpty()) {
                throw ApiException.badRequest("NO_SIZES", variant.getName() + " has no sizes yet. Add them on the Stock page.");
            }
            if (rows.size() > 1) {
                throw ApiException.badRequest("SIZE_REQUIRED", "Choose the size of " + variant.getName());
            }
        }
        Row row = rows.getFirst();
        return new StockPick(variant.getId(), variant.getName(), row.id(), row.legacy() ? null : row.size());
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
        if (!holds && !wholeBack) {
            // Cancelled or deleted: pieces the customer sent back (returns that still stand) aren't on the shelf.
            consumedByReturns(order).forEach((itemId, qty) -> desired.merge(itemId, qty, Integer::sum));
        }
        if (holds) {
            for (OrderItem item : order.getItems()) {
                if (item.getStockItemId() != null) {
                    desired.merge(item.getStockItemId(), item.getQuantity(), Integer::sum);
                }
            }
            jdbc.sql("""
                            select oi.stock_item_id, sum(ri.quantity) from order_return_items ri
                            join order_returns r on r.id = ri.return_id
                            join order_items oi on oi.id = ri.order_item_id
                            where r.order_id = :id and r.cancelled_at is null and r.restock and oi.stock_item_id is not null
                            group by oi.stock_item_id
                            """)
                    .param("id", order.getId())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                    .forEach(e -> desired.merge(e.getKey(), -e.getValue(), Integer::sum));
        }
        if (order.isExchange() && order.isExchangeRestocked() && !order.isDeleted() && order.getDeliveredAt() != null
                && order.getExchangeReason() != ExchangeReason.DEFECTIVE) {
            // Exchanges delivered before old items stopped being restocked put them back then (a negative holding).
            jdbc.sql("""
                            select oi.stock_item_id, ei.quantity from order_exchange_items ei
                            join order_items oi on oi.id = ei.original_item_id
                            where ei.exchange_order_id = :id and oi.stock_item_id is not null
                            """)
                    .param("id", order.getId())
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2)))
                    .list()
                    .forEach(e -> desired.merge(e.getKey(), -e.getValue(), Integer::sum));
        }

        Map<Long, Integer> held = new HashMap<>();
        jdbc.sql("select stock_item_id, -sum(change) from stock_movements where order_id = :id group by stock_item_id")
                .param("id", order.getId())
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2)))
                .list()
                .forEach(e -> held.put(e.getKey(), e.getValue()));

        // Positive delta = take from the shelf, negative = give back. Applied in id order to avoid deadlocks.
        Map<Long, Integer> deltas = new TreeMap<>();
        Set<Long> all = new HashSet<>(desired.keySet());
        all.addAll(held.keySet());
        for (Long itemId : all) {
            int delta = desired.getOrDefault(itemId, 0) - held.getOrDefault(itemId, 0);
            if (delta != 0) {
                deltas.put(itemId, delta);
            }
        }
        if (deltas.isEmpty()) {
            return;
        }
        // List every shortage at once ("only 3 of Black / M left, White / L is out of stock").
        Map<Long, ItemRow> current = itemRows(deltas.keySet());
        List<String> short_ = new ArrayList<>();
        for (var e : deltas.entrySet()) {
            ItemRow row = current.get(e.getKey());
            if (row != null && e.getValue() > row.quantity()) {
                short_.add(shortage(row));
            }
        }
        if (!short_.isEmpty()) {
            throw outOfStock(short_);
        }
        for (var e : deltas.entrySet()) {
            if (current.containsKey(e.getKey())) {
                apply(e.getKey(), -e.getValue(), reason, order.getId(), order.getOrderNumber(), userId);
            }
        }
    }

    /** Pieces taken back by the order's returns that still stand and didn't restock, per stock item. */
    private Map<Long, Integer> consumedByReturns(Order order) {
        Map<Long, Integer> consumed = new HashMap<>();
        List<Long> wholeReturns = jdbc.sql("""
                        select r.id from order_returns r
                        where r.order_id = :id and r.cancelled_at is null and not r.restock
                          and not exists (select 1 from order_return_items i where i.return_id = r.id)
                        """)
                .param("id", order.getId()).query(Long.class).list();
        if (!wholeReturns.isEmpty()) {
            // A whole-order return (refused parcel, or everything sent back): all of the order's pieces.
            for (OrderItem item : order.getItems()) {
                if (item.getStockItemId() != null) {
                    consumed.merge(item.getStockItemId(), item.getQuantity(), Integer::sum);
                }
            }
            return consumed;
        }
        jdbc.sql("""
                        select oi.stock_item_id, sum(ri.quantity) from order_return_items ri
                        join order_returns r on r.id = ri.return_id
                        join order_items oi on oi.id = ri.order_item_id
                        where r.order_id = :id and r.cancelled_at is null and not r.restock and oi.stock_item_id is not null
                        group by oi.stock_item_id
                        """)
                .param("id", order.getId())
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getInt(2))).list()
                .forEach(e -> consumed.merge(e.getKey(), e.getValue(), Integer::sum));
        return consumed;
    }

    // ---- Variants -----------------------------------------------------------------------------------------------

    /** @param sort {@code name}, {@code quantity} or {@code updatedAt} */
    @Transactional(readOnly = true)
    public PageResponse<VariantView> list(String query, boolean lowOnly, String sort, boolean descending, int page, int size) {
        String column = switch (sort) {
            case "quantity" -> "v.quantity";
            case "updatedAt" -> "v.updated_at";
            default -> "lower(v.name)";
        };
        String where = """
                from stock_variants v
                where v.deleted_at is null and lower(v.name) like :pattern escape '\\'
                  and (not :low or exists (select 1 from stock_items i join sizes s on s.id = i.size_id
                                           where i.variant_id = v.id and %s and i.quantity <= :threshold))
                """.formatted(SHOWN);
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> params = new HashMap<>();
        params.put("pattern", "%" + escape(term) + "%");
        params.put("low", lowOnly);
        params.put("threshold", lowThreshold);
        long total = jdbc.sql("select count(*) " + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<Long> ids = jdbc.sql("select v.id " + where + " order by " + column + (descending ? " desc" : " asc")
                        + ", v.id limit :limit offset :offset")
                .params(params).query(Long.class).list();
        return new PageResponse<>(views(ids), page, size, total, (int) Math.ceil(total / (double) size));
    }

    /** Variants whose name contains the text (those starting with it first), with their sizes, for the order form. */
    @Transactional(readOnly = true)
    public List<VariantSuggestion> suggest(String query, int limit) {
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Long> ids = jdbc.sql("""
                        select id from stock_variants
                        where deleted_at is null and lower(name) like :pattern escape '\\'
                        order by (lower(name) like :prefix escape '\\') desc, lower(name)
                        limit :limit
                        """)
                .param("pattern", "%" + escape(term) + "%")
                .param("prefix", escape(term) + "%")
                .param("limit", limit)
                .query(Long.class).list();
        return views(ids).stream().map(v -> new VariantSuggestion(v.id(), v.name(), v.quantity(), v.sizes())).toList();
    }

    @Transactional(readOnly = true)
    public List<ItemView> sizesOf(long variantId) {
        variants.findById(variantId).orElseThrow(() -> ApiException.notFound("Variant", variantId));
        return views(List.of(variantId)).getFirst().sizes();
    }

    @Transactional(readOnly = true)
    public StockSummary summary() {
        return jdbc.sql("""
                        select (select count(*) from stock_variants where deleted_at is null),
                               (select coalesce(sum(quantity), 0) from stock_variants where deleted_at is null),
                               (select count(*) from stock_items i join sizes s on s.id = i.size_id
                                join stock_variants v on v.id = i.variant_id
                                where v.deleted_at is null and %s and i.quantity <= :low)
                        """.formatted(SHOWN))
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
        // Check every variant's sizes before saving anything.
        Map<CreateVariantRequest, Map<Long, Integer>> plans = new LinkedHashMap<>();
        for (CreateVariantRequest r : byName.values()) {
            plans.put(r, newSizes(cleanName(r.name()), r.sizes(), r.quantity()));
        }
        List<Long> ids = new ArrayList<>();
        for (var plan : plans.entrySet()) {
            int total = plan.getValue().values().stream().mapToInt(Integer::intValue).sum();
            StockVariant v = variants.save(new StockVariant(cleanName(plan.getKey().name()), total));
            for (var size : plan.getValue().entrySet()) {
                long itemId = insertItem(v.getId(), size.getKey(), size.getValue());
                if (size.getValue() > 0) {
                    movements.save(new StockMovement(v.getId(), itemId, size.getValue(), size.getValue(), StockReason.ADDED,
                            null, null, userId, clock.instant()));
                }
            }
            ids.add(v.getId());
        }
        return views(ids);
    }

    /** Renames a variant and sets each size's quantity (see {@link UpdateVariantRequest}). */
    @Transactional
    public VariantView update(long id, UpdateVariantRequest request, long userId) {
        StockVariant v = lockVariant(id);
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

        List<ItemRow> current = itemsOf(id);
        Map<Long, ItemRow> bySize = current.stream().collect(Collectors.toMap(ItemRow::sizeId, r -> r));
        Map<Long, Integer> target = new LinkedHashMap<>();
        if (request.sizes() != null) {
            for (SizeQuantity sq : request.sizes()) {
                if (target.put(sq.sizeId(), sq.quantity()) != null) {
                    throw ApiException.badRequest("SIZE_REPEATED", "A size is listed more than once");
                }
            }
            List<Long> added = target.keySet().stream().filter(s -> !bySize.containsKey(s)).toList();
            requireUsableSizes(added);
        } else if (request.quantity() != null) {
            List<ItemRow> shown = current.stream().filter(ItemRow::shown).toList();
            if (shown.size() != 1) {
                throw ApiException.badRequest("SIZES_REQUIRED", "Give the quantity of each size of " + v.getName());
            }
            target.put(shown.getFirst().sizeId(), request.quantity());
        } else {
            throw ApiException.badRequest("SIZES_REQUIRED", "Give the quantity of each size of " + v.getName());
        }

        // A size taken off the variant must be empty first, so no pieces disappear unnoticed.
        List<String> notEmpty = current.stream()
                .filter(r -> r.active() && !target.containsKey(r.sizeId()) && r.quantity() > 0)
                .map(r -> r.sizeLabel() + " (" + r.quantity() + ")").toList();
        if (!notEmpty.isEmpty()) {
            throw ApiException.badRequest("SIZE_NOT_EMPTY", "Set these sizes to 0 before taking them off: " + String.join(", ", notEmpty));
        }
        Instant now = clock.instant();
        for (ItemRow r : current) {
            if (r.active() && !target.containsKey(r.sizeId())) {
                setActive(r.id(), false, now);
            }
        }
        String note = blankToNull(request.note());
        for (var t : target.entrySet()) {
            ItemRow existing = bySize.get(t.getKey());
            if (existing == null) {
                long itemId = insertItem(id, t.getKey(), 0);
                if (t.getValue() > 0) {
                    apply(itemId, t.getValue(), StockReason.ADJUSTED, null, note, userId);
                }
            } else {
                if (!existing.active()) {
                    setActive(existing.id(), true, now);
                }
                if (t.getValue() != existing.quantity()) {
                    apply(existing.id(), t.getValue() - existing.quantity(), StockReason.ADJUSTED, null, note, userId);
                }
            }
        }
        v.touch(now);
        variants.flush(); // so the answer carries the new version
        return views(List.of(id)).getFirst();
    }

    /** Sets, adds or removes quantities of several variant sizes at once; nothing changes if one would go below zero. */
    @Transactional
    public List<VariantView> updateAll(List<BulkUpdateItem> items, String note, long userId) {
        Map<Long, BulkUpdateItem> byItem = new LinkedHashMap<>();
        for (BulkUpdateItem item : items) {
            long itemId = item.stockItemId() != null ? item.stockItemId() : singleItemOf(item.id());
            if (byItem.put(itemId, item) != null) {
                throw ApiException.badRequest("VARIANT_REPEATED", "A variant size is listed more than once");
            }
        }
        Map<Long, ItemRow> rows = lockItems(byItem.keySet());
        if (rows.size() != byItem.size()) {
            throw ApiException.badRequest("VARIANT_UNKNOWN", "A variant no longer exists. Reload the list.");
        }
        List<String> problems = new ArrayList<>();
        Map<Long, Integer> deltas = new TreeMap<>();
        for (var e : byItem.entrySet()) {
            ItemRow row = rows.get(e.getKey());
            BulkUpdateItem item = e.getValue();
            int target = switch (item.mode()) {
                case SET -> item.amount();
                case ADD -> row.quantity() + item.amount();
                case REMOVE -> row.quantity() - item.amount();
            };
            if (target < 0) {
                problems.add(row.label() + " has only " + row.quantity());
            } else if (target > StockDtos.MAX_QUANTITY) {
                problems.add(row.label() + " would exceed " + StockDtos.MAX_QUANTITY);
            }
            deltas.put(e.getKey(), target - row.quantity());
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("NOT_ENOUGH_STOCK", "Cannot remove that many: " + String.join(", ", problems));
        }
        String cleanNote = blankToNull(note);
        Set<Long> touched = new LinkedHashSet<>();
        for (var e : deltas.entrySet()) {
            if (e.getValue() != 0) {
                apply(e.getKey(), e.getValue(), StockReason.ADJUSTED, null, cleanNote, userId);
            }
        }
        for (Long itemId : byItem.keySet()) {
            touched.add(rows.get(itemId).variantId());
        }
        Instant now = clock.instant();
        for (Long variantId : touched) {
            variants.findById(variantId).ifPresent(v -> v.touch(now));
        }
        variants.flush();
        return views(new ArrayList<>(touched));
    }

    /**
     * Moves a variant into a size of another variant: "Navy Blue - M" becomes Navy Blue, size M. Its pieces, and the
     * orders that hold them, move across; the old variant is removed. Only a variant with a single size can be merged.
     */
    @Transactional
    public VariantView merge(long sourceId, MergeRequest request, long userId) {
        if (sourceId == request.targetVariantId()) {
            throw ApiException.badRequest("SAME_VARIANT", "Choose a different variant to merge into");
        }
        // Lock both rows in id order.
        StockVariant first = lockVariant(Math.min(sourceId, request.targetVariantId()));
        StockVariant second = lockVariant(Math.max(sourceId, request.targetVariantId()));
        StockVariant source = first.getId() == sourceId ? first : second;
        StockVariant target = source == first ? second : first;
        requireUsableSizes(List.of(request.sizeId()));

        List<ItemRow> sourceItems = itemsOf(sourceId).stream().filter(ItemRow::active).toList();
        if (sourceItems.size() > 1) {
            throw ApiException.badRequest("MERGE_MULTIPLE_SIZES",
                    source.getName() + " already has several sizes; only a variant with a single size can be merged");
        }
        ItemRow targetItem = itemsOf(target.getId()).stream().filter(r -> r.sizeId() == request.sizeId()).findFirst().orElse(null);
        Instant now = clock.instant();
        long targetItemId;
        if (targetItem == null) {
            targetItemId = insertItem(target.getId(), request.sizeId(), 0);
        } else {
            targetItemId = targetItem.id();
            if (!targetItem.active()) {
                setActive(targetItemId, true, now);
            }
        }
        String sizeName = sizes.findById(request.sizeId()).map(Size::getName).orElseThrow();
        for (ItemRow item : itemsOf(sourceId)) {
            if (item.quantity() > 0) {
                apply(item.id(), -item.quantity(), StockReason.MERGED, null,
                        "Moved to " + target.getName() + " / " + sizeName, userId);
                apply(targetItemId, item.quantity(), StockReason.MERGED, null, "Merged from " + source.getName(), userId);
            }
            // Orders that picked the old variant now hold the new size, so cancelling them gives back the right pieces.
            jdbc.sql("update order_items set variant_id = :variant, stock_item_id = :target where stock_item_id = :source")
                    .param("variant", target.getId()).param("target", targetItemId).param("source", item.id()).update();
            jdbc.sql("""
                            update stock_movements set variant_id = :variant, stock_item_id = :target
                            where stock_item_id = :source and order_id is not null
                            """)
                    .param("variant", target.getId()).param("target", targetItemId).param("source", item.id()).update();
            setActive(item.id(), false, now);
        }
        source.markDeleted(now);
        target.touch(now);
        variants.flush();
        return views(List.of(target.getId())).getFirst();
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
        Set<Long> itemIds = page.getContent().stream().map(StockMovement::getStockItemId).collect(Collectors.toSet());
        Map<Long, String> sizeNames = new HashMap<>();
        if (!itemIds.isEmpty()) {
            jdbc.sql("select i.id, s.name, s.legacy from stock_items i join sizes s on s.id = i.size_id where i.id in (:ids)")
                    .param("ids", itemIds)
                    .query((rs, i) -> Map.entry(rs.getLong(1), rs.getBoolean(3) ? NO_SIZE : rs.getString(2))).list()
                    .forEach(e -> sizeNames.put(e.getKey(), e.getValue()));
        }
        return PageResponse.of(page, m -> new MovementView(m.getId(), m.getChange(), m.getQuantityAfter(),
                sizeNames.get(m.getStockItemId()), m.getReason().name(), m.getReason().label(), m.getOrderId(),
                m.getOrderId() == null ? null : numbers.get(m.getOrderId()),
                m.getOrderId() == null ? m.getNote() : null,
                m.getCreatedBy() == null ? "System" : UserDirectory.nameOf(names, m.getCreatedBy()), m.getCreatedAt()));
    }

    // ---- Sizes (admin) --------------------------------------------------------------------------------------------

    /** The managed size list in order, with how many variants carry each size and how many pieces are in it. */
    @Transactional(readOnly = true)
    public List<SizeView> sizes() {
        return jdbc.sql("""
                        select s.id, s.name, s.position, s.active,
                               (select count(*) from stock_items i join stock_variants v on v.id = i.variant_id
                                where i.size_id = s.id and i.active and v.deleted_at is null),
                               (select coalesce(sum(i.quantity), 0) from stock_items i join stock_variants v on v.id = i.variant_id
                                where i.size_id = s.id and v.deleted_at is null)
                        from sizes s where not s.legacy order by s.position, s.id
                        """)
                .query((rs, i) -> new SizeView(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
                        rs.getLong(5), rs.getLong(6)))
                .list();
    }

    @Transactional
    public List<SizeView> createSize(SizeRequest request) {
        String name = cleanSize(request.name());
        if (sizes.nameTaken(name, 0L)) {
            throw ApiException.conflict("SIZE_EXISTS", "Size " + name + " already exists");
        }
        sizes.save(new Size(name, sizes.maxPosition() + 1));
        sizes.flush(); // the list below is read with SQL
        return sizes();
    }

    @Transactional
    public List<SizeView> updateSize(long id, SizeRequest request) {
        Size size = managedSize(id);
        String name = cleanSize(request.name());
        if (!name.equals(size.getName())) {
            if (sizes.nameTaken(name, id)) {
                throw ApiException.conflict("SIZE_EXISTS", "Size " + name + " already exists");
            }
            size.rename(name);
        }
        if (request.active() != null) {
            size.setActive(request.active());
        }
        sizes.flush(); // the list below is read with SQL
        return sizes();
    }

    /** Puts the sizes in the given order (every size, once). */
    @Transactional
    public List<SizeView> reorderSizes(List<Long> ids) {
        List<Size> managed = sizes.managed();
        Set<Long> known = managed.stream().map(Size::getId).collect(Collectors.toSet());
        if (ids.size() != known.size() || !known.equals(new HashSet<>(ids))) {
            throw ApiException.badRequest("SIZE_ORDER_INCOMPLETE", "List every size exactly once. Reload and try again.");
        }
        Map<Long, Size> byId = managed.stream().collect(Collectors.toMap(Size::getId, s -> s));
        for (int i = 0; i < ids.size(); i++) {
            byId.get(ids.get(i)).moveTo(i + 1);
        }
        sizes.flush(); // the list below is read with SQL
        return sizes();
    }

    /** Only a size no variant ever used can be deleted; otherwise it is switched off. */
    @Transactional
    public List<SizeView> deleteSize(long id) {
        Size size = managedSize(id);
        boolean used = Boolean.TRUE.equals(jdbc.sql("select exists(select 1 from stock_items where size_id = :id)")
                .param("id", id).query(Boolean.class).single());
        if (used) {
            throw ApiException.conflict("SIZE_IN_USE", "Variants use size " + size.getName() + ". Switch it off instead.");
        }
        sizes.delete(size);
        sizes.flush(); // the list below is read with SQL
        return sizes();
    }

    // ---- Internals ----------------------------------------------------------------------------------------------

    /** A stock item with its variant and size. */
    private record ItemRow(long id, long variantId, String variantName, long sizeId, String sizeName, boolean legacy,
            int quantity, boolean active) {

        String sizeLabel() {
            return legacy ? NO_SIZE : sizeName;
        }

        String label() {
            return legacy ? variantName : variantName + " / " + sizeName;
        }

        boolean shown() {
            return active && !(legacy && quantity == 0);
        }
    }

    private static final String ITEM_COLUMNS = """
            select i.id, i.variant_id, v.name, s.id, s.name, s.legacy, i.quantity, i.active
            from stock_items i join stock_variants v on v.id = i.variant_id join sizes s on s.id = i.size_id
            """;

    private static ItemRow itemRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ItemRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getBoolean(6), rs.getInt(7), rs.getBoolean(8));
    }

    private Map<Long, ItemRow> itemRows(Set<Long> ids) {
        return jdbc.sql(ITEM_COLUMNS + " where i.id in (:ids)").param("ids", ids)
                .query((rs, i) -> itemRow(rs)).list().stream()
                .collect(Collectors.toMap(ItemRow::id, r -> r));
    }

    private Map<Long, ItemRow> lockItems(Set<Long> ids) {
        return jdbc.sql(ITEM_COLUMNS + " where i.id in (:ids) and v.deleted_at is null order by i.id for update of i")
                .param("ids", ids).query((rs, i) -> itemRow(rs)).list().stream()
                .collect(Collectors.toMap(ItemRow::id, r -> r));
    }

    private List<ItemRow> itemsOf(long variantId) {
        return jdbc.sql(ITEM_COLUMNS + " where i.variant_id = :id order by s.position, s.id").param("id", variantId)
                .query((rs, i) -> itemRow(rs)).list();
    }

    /** The only shown size of a variant (for callers that address a variant without a size). */
    private long singleItemOf(Long variantId) {
        if (variantId == null) {
            throw ApiException.badRequest("VARIANT_UNKNOWN", "Say which variant size to change");
        }
        List<ItemRow> shown = itemsOf(variantId).stream().filter(ItemRow::shown).toList();
        if (shown.size() != 1) {
            throw ApiException.badRequest("SIZE_REQUIRED", "This variant has several sizes; change each size instead");
        }
        return shown.getFirst().id();
    }

    /**
     * The one place quantities change: adds {@code change} to a stock item (and its variant's total) unless that would
     * go below zero, and records the movement. Returns the item's new quantity.
     */
    private int apply(long itemId, int change, StockReason reason, Long orderId, String note, Long userId) {
        Instant now = clock.instant();
        record Updated(int quantity, long variantId) {
        }
        Updated updated = jdbc.sql("""
                        update stock_items set quantity = quantity + :change, updated_at = :now
                        where id = :id and quantity + :change >= 0
                        returning quantity, variant_id
                        """)
                .param("change", change).param("now", Timestamp.from(now)).param("id", itemId)
                .query((rs, i) -> new Updated(rs.getInt(1), rs.getLong(2))).optional()
                .orElseThrow(() -> {
                    // Someone took the last pieces between the check and now.
                    ItemRow row = itemRows(Set.of(itemId)).get(itemId);
                    return outOfStock(List.of(row == null ? "a variant is out of stock" : shortage(row)));
                });
        jdbc.sql("update stock_variants set quantity = quantity + :change where id = :id")
                .param("change", change).param("id", updated.variantId()).update();
        movements.save(new StockMovement(updated.variantId(), itemId, change, updated.quantity(), reason, orderId, note,
                userId, now));
        return updated.quantity();
    }

    private long insertItem(long variantId, long sizeId, int quantity) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.sql("""
                        insert into stock_items (variant_id, size_id, quantity, created_at, updated_at)
                        values (:variant, :size, :quantity, :now, :now) returning id
                        """)
                .param("variant", variantId).param("size", sizeId).param("quantity", quantity).param("now", now)
                .query(Long.class).single();
    }

    private void setActive(long itemId, boolean active, Instant now) {
        jdbc.sql("update stock_items set active = :active, updated_at = :now where id = :id")
                .param("active", active).param("now", Timestamp.from(now)).param("id", itemId).update();
    }

    /** The sizes (and quantities) a new variant starts with. */
    private Map<Long, Integer> newSizes(String name, List<SizeQuantity> requested, Integer quantity) {
        Map<Long, Integer> plan = new LinkedHashMap<>();
        if (requested != null && !requested.isEmpty()) {
            for (SizeQuantity sq : requested) {
                if (plan.put(sq.sizeId(), sq.quantity()) != null) {
                    throw ApiException.badRequest("SIZE_REPEATED", name + ": a size is listed more than once");
                }
            }
            requireUsableSizes(plan.keySet());
        } else if (quantity != null) {
            plan.put(legacySizeId(), quantity);
        } else {
            throw ApiException.badRequest("SIZES_REQUIRED", name + ": choose at least one size");
        }
        return plan;
    }

    /** Sizes that can be added to a variant: in the list and switched on. */
    private void requireUsableSizes(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        List<Size> found = sizes.findAllById(ids);
        if (found.size() != new HashSet<>(ids).size() || found.stream().anyMatch(Size::isLegacy)) {
            throw ApiException.badRequest("SIZE_UNKNOWN", "A size no longer exists. Reload and try again.");
        }
        List<String> off = found.stream().filter(s -> !s.isActive()).map(Size::getName).toList();
        if (!off.isEmpty()) {
            throw ApiException.badRequest("SIZE_INACTIVE", "Switched off: " + String.join(", ", off));
        }
    }

    private long legacySizeId() {
        return jdbc.sql("select id from sizes where legacy").query(Long.class).single();
    }

    private Size managedSize(long id) {
        Size size = sizes.findById(id).orElseThrow(() -> ApiException.notFound("Size", id));
        if (size.isLegacy()) {
            throw ApiException.badRequest("SIZE_FIXED", "\"No size\" can't be changed");
        }
        return size;
    }

    private StockVariant lockVariant(long id) {
        List<Long> found = jdbc.sql("select id from stock_variants where id = :id and deleted_at is null for update")
                .param("id", id).query(Long.class).list();
        if (found.isEmpty()) {
            throw ApiException.notFound("Variant", id);
        }
        return variants.findById(id).orElseThrow(() -> ApiException.notFound("Variant", id));
    }

    /** Variants with their shown sizes, in the order of {@code ids}. */
    private List<VariantView> views(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        record Head(long id, String name, int quantity, Instant updatedAt, long version) {
        }
        Map<Long, Head> heads = jdbc.sql("select id, name, quantity, updated_at, version from stock_variants where id in (:ids)")
                .param("ids", ids)
                .query((rs, i) -> new Head(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toInstant(), rs.getLong(5)))
                .list().stream().collect(Collectors.toMap(Head::id, h -> h));
        Map<Long, List<ItemView>> items = new HashMap<>();
        jdbc.sql(ITEM_COLUMNS + " where i.variant_id in (:ids) and " + SHOWN + " order by s.position, s.id")
                .param("ids", ids).query((rs, i) -> itemRow(rs)).list()
                .forEach(r -> items.computeIfAbsent(r.variantId(), k -> new ArrayList<>())
                        .add(new ItemView(r.id(), r.sizeId(), r.sizeLabel(), r.legacy(), r.quantity(), r.quantity() <= lowThreshold)));
        List<VariantView> result = new ArrayList<>();
        for (Long id : ids) {
            Head h = heads.get(id);
            if (h == null) {
                continue;
            }
            List<ItemView> sizesOf = items.getOrDefault(id, List.of());
            boolean low = sizesOf.isEmpty() || sizesOf.stream().anyMatch(ItemView::low);
            result.add(new VariantView(h.id(), h.name(), h.quantity(), low, sizesOf, h.updatedAt(), h.version()));
        }
        return result;
    }

    private static String shortage(ItemRow row) {
        return row.quantity() == 0 ? row.label() + " is out of stock" : "only " + row.quantity() + " of " + row.label() + " left";
    }

    private static ApiException outOfStock(List<String> shortages) {
        String message = String.join(", ", shortages);
        return ApiException.conflict("OUT_OF_STOCK",
                "Not enough stock: " + Character.toUpperCase(message.charAt(0)) + message.substring(1) + ".");
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 80) {
            throw ApiException.badRequest("INVALID_VARIANT_NAME", "A variant name must be 1–80 characters");
        }
        return clean;
    }

    private static String cleanSize(String name) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 20) {
            throw ApiException.badRequest("INVALID_SIZE_NAME", "A size must be 1–20 characters");
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
