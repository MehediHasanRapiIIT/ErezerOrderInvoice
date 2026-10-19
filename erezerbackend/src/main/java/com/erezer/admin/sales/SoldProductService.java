package com.erezer.admin.sales;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sold products by category. The category is the product name typed on the order, grouped ignoring capitals and
 * extra spaces ("T-Shirt", "t-shirt " → one category), shown with its most common spelling.
 *
 * <p>Rules:
 * <ul>
 *   <li>Only <b>delivered</b> sales count (the dashboard's rule); exchanges are left out (they appear under
 *       Exchanges on the dashboard), and so are returned orders.</li>
 *   <li>The order's discount, delivery charge and courier cost are shared between its categories <b>by pieces</b>:
 *       2 tees and 1 cap in one order → the tees carry 2/3, the cap 1/3.</li>
 *   <li>Sale = the items' price − their share of the discount (no delivery charge). Courier cost = the courier's
 *       real charge when known, otherwise the delivery charge.</li>
 *   <li>Dates are the delivery day in Asia/Dhaka.</li>
 * </ul>
 */
@Service
public class SoldProductService {

    static final int MAX_EXPORT_ROWS = 50_000;

    /** One row per order and category, with its share of the order-level amounts. */
    private static final String ROWS = """
            with returned as (
                select ri.order_item_id, sum(ri.quantity) as qty
                from order_return_items ri join order_returns r on r.id = ri.return_id
                where r.cancelled_at is null
                group by ri.order_item_id
            ),
            -- Pieces the customer kept: sent-back pieces (partly returned orders) are not sold.
            lines as (
                select oi.order_id,
                       lower(regexp_replace(btrim(oi.product_name), '\\s+', ' ', 'g')) as cat_key,
                       regexp_replace(btrim(oi.product_name), '\\s+', ' ', 'g') as cat_name,
                       oi.quantity - coalesce(ret.qty, 0) as quantity,
                       oi.line_total * (oi.quantity - coalesce(ret.qty, 0)) / oi.quantity as line_total
                from order_items oi
                join orders o on o.id = oi.order_id
                left join returned ret on ret.order_item_id = oi.id
                where o.deleted_at is null and o.order_type = 'SALE' and o.status = 'DELIVERED'
                  and o.delivered_at >= :start and o.delivered_at < :end
                  and oi.quantity - coalesce(ret.qty, 0) > 0
            ),
            order_pieces as (select order_id, sum(quantity) as pieces from lines group by order_id),
            names as (select cat_key, mode() within group (order by cat_name) as cat_name from lines group by cat_key),
            grouped as (
                select order_id, cat_key, sum(quantity) as pieces, sum(line_total) as line_total
                from lines group by order_id, cat_key
            ),
            -- Each category's share by pieces, rounded to paisa...
            shared as (
                select g.order_id, g.cat_key, g.pieces, g.line_total,
                       o.discount, o.delivery_charge as delivery, coalesce(o.courier_charge, o.delivery_charge) as courier,
                       round(o.discount * g.pieces / p.pieces, 2) as discount_share,
                       round(o.delivery_charge * g.pieces / p.pieces, 2) as delivery_share,
                       round(coalesce(o.courier_charge, o.delivery_charge) * g.pieces / p.pieces, 2) as courier_share,
                       row_number() over (partition by g.order_id order by g.pieces desc, g.cat_key) as rn
                from grouped g join order_pieces p on p.order_id = g.order_id join orders o on o.id = g.order_id
            ),
            -- ...and the order's largest category takes the rounding leftover, so shares add up to the order exactly.
            fixed as (
                select *,
                       discount_share + case when rn = 1 then discount - sum(discount_share) over w else 0 end as discount_fixed,
                       delivery_share + case when rn = 1 then delivery - sum(delivery_share) over w else 0 end as delivery_fixed,
                       courier_share + case when rn = 1 then courier - sum(courier_share) over w else 0 end as courier_fixed
                from shared
                window w as (partition by order_id)
            )
            select f.order_id, o.order_number, o.delivered_at, f.cat_key, n.cat_name, f.pieces,
                   f.line_total - f.discount_fixed as sale,
                   f.delivery_fixed as delivery,
                   f.courier_fixed as courier
            from fixed f
            join orders o on o.id = f.order_id
            join names n on n.cat_key = f.cat_key
            where (:category = '' or f.cat_key = :category)
              and (:q = '' or o.order_number ilike :qPattern)
            """;

    private final JdbcClient jdbc;
    private final AppProperties appProperties;

    SoldProductService(JdbcClient jdbc, AppProperties appProperties) {
        this.jdbc = jdbc;
        this.appProperties = appProperties;
    }

    public record Filter(LocalDate from, LocalDate to, String category, String query) {
    }

    public record SoldRow(long orderId, String orderNumber, Instant deliveredAt, String categoryKey, String category,
            long pieces, BigDecimal sale, BigDecimal deliveryCharge, BigDecimal courierCost) {
    }

    public record CategoryTotal(String categoryKey, String category, long orders, long pieces, BigDecimal sale,
            BigDecimal deliveryCharge, BigDecimal courierCost) {
    }

    /**
     * @param categories every category sold in the range (ignores the category filter, so it can fill the filter)
     * @param totals     totals for the current filter
     */
    public record Summary(LocalDate from, LocalDate to, List<CategoryTotal> categories, CategoryTotal totals) {
    }

    @Transactional(readOnly = true)
    public PageResponse<SoldRow> rows(Filter filter, int page, int size) {
        Map<String, Object> params = params(filter);
        long total = jdbc.sql("select count(*) from (" + ROWS + ") x").params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<SoldRow> content = jdbc.sql("select * from (" + ROWS + ") x order by delivered_at desc, order_id desc, cat_key limit :limit offset :offset")
                .params(params).query((rs, i) -> row(rs)).list();
        return new PageResponse<>(content, page, size, total, (int) Math.ceil(total / (double) size));
    }

    @Transactional(readOnly = true)
    public List<SoldRow> allRows(Filter filter) {
        Map<String, Object> params = params(filter);
        params.put("limit", MAX_EXPORT_ROWS + 1);
        List<SoldRow> rows = jdbc.sql("select * from (" + ROWS + ") x order by delivered_at desc, order_id desc, cat_key limit :limit")
                .params(params).query((rs, i) -> row(rs)).list();
        if (rows.size() > MAX_EXPORT_ROWS) {
            throw ApiException.badRequest("EXPORT_TOO_LARGE", "More than " + MAX_EXPORT_ROWS + " rows match. Narrow the dates and export again.");
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Summary summary(Filter filter) {
        Map<String, Object> all = params(new Filter(filter.from(), filter.to(), null, filter.query()));
        List<CategoryTotal> categories = jdbc.sql("""
                        select cat_key, cat_name, count(distinct order_id), sum(pieces), sum(sale), sum(delivery), sum(courier)
                        from (""" + ROWS + """
                        ) x group by cat_key, cat_name order by sum(sale) desc, cat_name
                        """)
                .params(all).query((rs, i) -> new CategoryTotal(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7)))
                .list();
        CategoryTotal totals = jdbc.sql("""
                        select count(distinct order_id), coalesce(sum(pieces), 0), coalesce(sum(sale), 0),
                               coalesce(sum(delivery), 0), coalesce(sum(courier), 0)
                        from (""" + ROWS + ") x")
                .params(params(filter)).query((rs, i) -> new CategoryTotal(null, "Total", rs.getLong(1), rs.getLong(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5)))
                .single();
        return new Summary(filter.from(), filter.to(), categories, totals);
    }

    /** Product names used on orders (most used first, one spelling each), for the Add Order suggestions. */
    @Transactional(readOnly = true)
    public List<String> productNames() {
        return jdbc.sql("""
                        select mode() within group (order by name) from (
                            select regexp_replace(btrim(oi.product_name), '\\s+', ' ', 'g') as name
                            from order_items oi join orders o on o.id = oi.order_id
                            where o.deleted_at is null
                        ) x
                        group by lower(name)
                        order by count(*) desc, lower(name)
                        limit 500
                        """)
                .query(String.class).list();
    }

    private Map<String, Object> params(Filter filter) {
        if (filter.from().isAfter(filter.to())) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "The start date must be on or before the end date");
        }
        var zone = appProperties.zoneId();
        Map<String, Object> params = new HashMap<>();
        params.put("start", Timestamp.from(filter.from().atStartOfDay(zone).toInstant()));
        params.put("end", Timestamp.from(filter.to().plusDays(1).atStartOfDay(zone).toInstant()));
        params.put("category", filter.category() == null ? "" : filter.category().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        String q = filter.query() == null ? "" : filter.query().trim();
        params.put("q", q);
        params.put("qPattern", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return params;
    }

    private static SoldRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SoldRow(rs.getLong("order_id"), rs.getString("order_number"), rs.getTimestamp("delivered_at").toInstant(),
                rs.getString("cat_key"), rs.getString("cat_name"), rs.getLong("pieces"), rs.getBigDecimal("sale"),
                rs.getBigDecimal("delivery"), rs.getBigDecimal("courier"));
    }
}
