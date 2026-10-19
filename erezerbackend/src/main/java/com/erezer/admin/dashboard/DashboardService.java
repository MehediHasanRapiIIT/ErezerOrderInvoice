package com.erezer.admin.dashboard;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.dashboard.DashboardSummary.CategoryTotal;
import com.erezer.admin.dashboard.DashboardSummary.OrderCounts;
import com.erezer.admin.dashboard.DashboardSummary.SeriesPoint;
import com.erezer.admin.dashboard.DashboardSummary.Totals;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregates for the dashboard, computed in SQL. All date boundaries are business days in Asia/Dhaka. */
@Service
public class DashboardService {

    /** Ranges longer than this are charted per month instead of per day. */
    static final int MAX_DAILY_BUCKETS = 62;
    static final int MAX_RANGE_DAYS = 366 * 5;
    private static final int TOP_CATEGORIES = 5;

    private final JdbcClient jdbc;
    private final AppProperties appProperties;

    DashboardService(JdbcClient jdbc, AppProperties appProperties) {
        this.jdbc = jdbc;
        this.appProperties = appProperties;
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "The start date must be on or before the end date");
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("RANGE_TOO_LONG", "Choose a range of at most five years");
        }
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);

        Totals current = totals(from, to);
        String granularity = days <= MAX_DAILY_BUCKETS ? "DAY" : "MONTH";
        return new DashboardSummary(from, to, current, totals(previousFrom, previousTo),
                orderCounts(from, to, current), exchanges(from, to), returns(from, to), granularity, series(from, to, granularity),
                topCategories(from, to));
    }

    /**
     * Cash-basis figures for a COD courier business.
     *
     * <ul>
     *   <li><b>Sales</b> are recognised on the day of delivery and stay in that period even if the order is
     *       returned later (the return is then booked as a refund in the period it happens).</li>
     *   <li><b>Refused at the door</b> (returned without ever being delivered): no sale, and the business pays
     *       the courier the delivery charge plus the return charge.</li>
     *   <li><b>Returned after delivery</b>: the customer already paid the delivery charge, so the cost is the
     *       courier's return charge plus refunding the product value.</li>
     *   <li>The delivery charge collected from customers is passed to the courier, so it is not profit, except the
     *       difference when the courier reported charging us less or more ({@code orders.courier_charge}).</li>
     *   <li>When the courier reported its real charge for a refused parcel, that charge is the cost of the return.</li>
     *   <li><b>Exchanges</b> (orders of type EXCHANGE) earn only a positive price difference ({@code product_amount});
     *       a cheaper replacement with a refund is an exchange refund; when Erezer pays the exchange delivery, the
     *       courier's charge is an exchange cost. All are booked on the exchange's delivery day.</li>
     * </ul>
     */
    private Totals totals(LocalDate from, LocalDate to) {
        Map<String, Object> range = range(from, to);
        // Every delivery in the period, including orders that were returned afterwards.
        var delivered = jdbc.sql("""
                        select count(*) as n, coalesce(sum(total), 0) as total,
                               coalesce(sum(product_amount), 0) as product,
                               coalesce(sum(delivery_collected), 0) as delivery,
                               coalesce(sum(case when order_type = 'EXCHANGE' and exchange_delivery_paid_by = 'EREZER' then 0
                                                 else delivery_charge - coalesce(courier_charge, delivery_charge) end), 0)
                                   as margin,
                               coalesce(sum(case when order_type = 'EXCHANGE' and exchange_delivery_paid_by = 'EREZER'
                                                     then coalesce(courier_charge, delivery_charge)
                                                 else 0 end), 0) as exchange_cost,
                               coalesce(sum(exchange_refund_amount), 0) as exchange_refunds,
                               coalesce(sum(coalesce(courier_charge, delivery_charge)), 0) as courier_delivery
                        from orders
                        where deleted_at is null and status in ('DELIVERED', 'RETURNED')
                          and delivered_at >= :start and delivered_at < :end
                        """)
                .params(range).query((rs, i) -> new Object[] {
                        rs.getLong("n"), rs.getBigDecimal("total"), rs.getBigDecimal("product"), rs.getBigDecimal("delivery"),
                        rs.getBigDecimal("margin"), rs.getBigDecimal("exchange_cost"), rs.getBigDecimal("exchange_refunds"),
                        rs.getBigDecimal("courier_delivery")})
                .single();
        // Returns recorded in the period (order_returns). The courier is always paid the return charge; a refused
        // parcel costs delivery + return (the courier's real charge when known). After delivery the customer is
        // refunded (less the charge when they pay it); a refused parcel the customer pays for is money recovered.
        var returned = jdbc.sql("""
                        select count(*) as n,
                               count(*) filter (where r.kind = 'REFUSED') as refused,
                               coalesce(sum(case when r.kind = 'REFUSED' then o.total else r.items_value end), 0) as total,
                               coalesce(sum(case when r.kind = 'REFUSED' then o.product_amount else r.items_value end), 0) as product,
                               coalesce(sum(case when r.kind = 'REFUSED'
                                                     then coalesce(o.courier_charge, o.delivery_charge + r.return_charge)
                                                 else r.return_charge end), 0) as courier_cost,
                               coalesce(sum(case when r.kind = 'AFTER_DELIVERY' then r.refund_amount else 0 end), 0) as refunds,
                               coalesce(sum(case when r.kind = 'REFUSED' and r.paid_by = 'CUSTOMER'
                                                     then coalesce(o.courier_charge, o.delivery_charge + r.return_charge)
                                                 else 0 end), 0) as recovered
                        from order_returns r
                        join orders o on o.id = r.order_id
                        where r.cancelled_at is null and o.deleted_at is null
                          and r.created_at >= :start and r.created_at < :end
                        """)
                .params(range).query((rs, i) -> new Object[] {
                        rs.getLong("n"), rs.getLong("refused"), rs.getBigDecimal("total"), rs.getBigDecimal("product"),
                        rs.getBigDecimal("courier_cost"), rs.getBigDecimal("refunds"), rs.getBigDecimal("recovered")})
                .single();
        var expenses = jdbc.sql("""
                        select count(*) as n, coalesce(sum(amount), 0) as total
                        from expenses
                        where deleted_at is null and expense_date between :fromDate and :toDate
                        """)
                .params(range).query((rs, i) -> new Object[] {rs.getLong("n"), rs.getBigDecimal("total")})
                .single();

        BigDecimal productRevenue = (BigDecimal) delivered[2];
        BigDecimal returnCourierCost = (BigDecimal) returned[4];
        BigDecimal refunds = (BigDecimal) returned[5];
        BigDecimal expenseTotal = (BigDecimal) expenses[1];
        BigDecimal deliveryDifference = (BigDecimal) delivered[4];
        BigDecimal exchangeCost = (BigDecimal) delivered[5];
        BigDecimal exchangeRefunds = (BigDecimal) delivered[6];
        BigDecimal recovered = (BigDecimal) returned[6];
        BigDecimal netProfit = productRevenue.add(deliveryDifference).subtract(refunds).subtract(exchangeRefunds)
                .subtract(returnCourierCost).add(recovered).subtract(exchangeCost).subtract(expenseTotal);
        return new Totals((BigDecimal) delivered[1], productRevenue, (BigDecimal) delivered[3], deliveryDifference,
                (BigDecimal) returned[2], (BigDecimal) returned[3], returnCourierCost, recovered, refunds, exchangeRefunds,
                exchangeCost, ((BigDecimal) delivered[7]).add(returnCourierCost), (BigDecimal) delivered[7], expenseTotal,
                netProfit,
                (long) delivered[0], (long) returned[0], (long) returned[1], (long) expenses[0]);
    }

    /** Returns recorded in the period, in items (a return without items is the whole order), by who paid. */
    private DashboardSummary.Returns returns(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select count(*),
                               coalesce(sum(q.items), 0),
                               coalesce(sum(q.items) filter (where r.paid_by = 'CUSTOMER'), 0),
                               coalesce(sum(q.items) filter (where r.paid_by = 'EREZER'), 0),
                               coalesce(sum(c.cost) filter (where r.paid_by = 'CUSTOMER'), 0),
                               coalesce(sum(c.cost) filter (where r.paid_by = 'EREZER'), 0)
                        from order_returns r
                        join orders o on o.id = r.order_id
                        cross join lateral (select coalesce(
                                (select sum(i.quantity) from order_return_items i where i.return_id = r.id),
                                (select sum(oi.quantity) from order_items oi where oi.order_id = r.order_id), 0) as items) q
                        cross join lateral (select case when r.kind = 'REFUSED'
                                                        then coalesce(o.courier_charge, o.delivery_charge + r.return_charge)
                                                    else r.return_charge end as cost) c
                        where r.cancelled_at is null and o.deleted_at is null
                          and r.created_at >= :start and r.created_at < :end
                        """)
                .params(range(from, to))
                .query((rs, i) -> new DashboardSummary.Returns(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6)))
                .single();
    }

    /** Exchanges delivered in the period, in items, split by who paid the exchange delivery. */
    private DashboardSummary.Exchanges exchanges(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select count(*) as exchanges,
                               coalesce(sum(i.items), 0) as items,
                               coalesce(sum(i.items) filter (where o.exchange_delivery_paid_by = 'CUSTOMER'), 0) as by_customer,
                               coalesce(sum(i.items) filter (where o.exchange_delivery_paid_by = 'EREZER'), 0) as by_erezer,
                               coalesce(sum(o.delivery_collected), 0) as collected,
                               coalesce(sum(coalesce(o.courier_charge, o.delivery_charge))
                                        filter (where o.exchange_delivery_paid_by = 'EREZER'), 0) as erezer_cost
                        from orders o
                        join (select order_id, sum(quantity) as items from order_items group by order_id) i
                          on i.order_id = o.id
                        where o.deleted_at is null and o.order_type = 'EXCHANGE' and o.status in ('DELIVERED', 'RETURNED')
                          and o.delivered_at >= :start and o.delivered_at < :end
                        """)
                .params(range(from, to))
                .query((rs, i) -> new DashboardSummary.Exchanges(rs.getLong("exchanges"), rs.getLong("items"),
                        rs.getLong("by_customer"), rs.getLong("by_erezer"), rs.getBigDecimal("collected"),
                        rs.getBigDecimal("erezer_cost")))
                .single();
    }

    private OrderCounts orderCounts(LocalDate from, LocalDate to, Totals current) {
        Map<String, Object> range = range(from, to);
        long placed = jdbc.sql("""
                        select count(*) from orders
                        where deleted_at is null and created_at >= :start and created_at < :end
                        """).params(range).query(Long.class).single();
        long cancelled = jdbc.sql("""
                        select count(*) from orders
                        where deleted_at is null and status = 'CANCELLED'
                          and cancelled_at >= :start and cancelled_at < :end
                        """).params(range).query(Long.class).single();
        // Of the parcels that reached an outcome (delivered, or refused at the door), how many came back.
        long outcomes = current.deliveredOrders() + current.refusedOrders();
        Double returnRate = outcomes == 0 ? null : Math.min(1.0, (double) current.returnedOrders() / outcomes);
        return new OrderCounts(placed, cancelled, current.deliveredOrders(), current.returnedOrders(), returnRate);
    }

    /** Product revenue and expenses per bucket, with empty buckets filled in as zero. */
    private List<SeriesPoint> series(LocalDate from, LocalDate to, String granularity) {
        Map<String, Object> params = new HashMap<>(range(from, to));
        String unit = granularity.equals("DAY") ? "day" : "month";
        params.put("unit", unit);

        Map<LocalDate, BigDecimal> revenue = new HashMap<>();
        jdbc.sql("""
                        select date_trunc(:unit, delivered_at at time zone :zone)::date as bucket,
                               sum(product_amount) as amount
                        from orders
                        where deleted_at is null and status in ('DELIVERED', 'RETURNED')
                          and delivered_at >= :start and delivered_at < :end
                        group by 1
                        """)
                .params(params)
                .query((rs, i) -> Map.entry(rs.getObject("bucket", LocalDate.class), rs.getBigDecimal("amount")))
                .list().forEach(e -> revenue.put(e.getKey(), e.getValue()));

        Map<LocalDate, BigDecimal> spent = new HashMap<>();
        jdbc.sql("""
                        select date_trunc(:unit, expense_date)::date as bucket, sum(amount) as amount
                        from expenses
                        where deleted_at is null and expense_date between :fromDate and :toDate
                        group by 1
                        """)
                .params(params)
                .query((rs, i) -> Map.entry(rs.getObject("bucket", LocalDate.class), rs.getBigDecimal("amount")))
                .list().forEach(e -> spent.put(e.getKey(), e.getValue()));

        List<SeriesPoint> points = new ArrayList<>();
        LocalDate bucket = unit.equals("day") ? from : from.withDayOfMonth(1);
        while (!bucket.isAfter(to)) {
            points.add(new SeriesPoint(bucket, revenue.getOrDefault(bucket, BigDecimal.ZERO),
                    spent.getOrDefault(bucket, BigDecimal.ZERO)));
            bucket = unit.equals("day") ? bucket.plusDays(1) : bucket.plusMonths(1);
        }
        return points;
    }

    private List<CategoryTotal> topCategories(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        select c.name, sum(e.amount) as total
                        from expenses e join expense_categories c on c.id = e.category_id
                        where e.deleted_at is null and e.expense_date between :fromDate and :toDate
                        group by c.name
                        order by total desc
                        limit :limit
                        """)
                .params(range(from, to)).param("limit", TOP_CATEGORIES)
                .query((rs, i) -> new CategoryTotal(rs.getString("name"), rs.getBigDecimal("total")))
                .list();
    }

    /** Instant bounds [start of from, start of the day after to) in the business zone, plus the plain dates. */
    private Map<String, Object> range(LocalDate from, LocalDate to) {
        var zone = appProperties.zoneId();
        return Map.of(
                "start", Timestamp.from(from.atStartOfDay(zone).toInstant()),
                "end", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()),
                "fromDate", from,
                "toDate", to,
                "zone", zone.getId());
    }
}
