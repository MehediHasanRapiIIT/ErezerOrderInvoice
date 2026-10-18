package com.erezer.admin.customer;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.customer.CustomerDtos.CustomerPage;
import com.erezer.admin.customer.CustomerDtos.CustomerRow;
import com.erezer.admin.customer.CustomerDtos.CustomerSummary;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers aggregated from orders, one per normalised phone number. Contact details come from the most
 * recent order, so a customer who moved shows their latest address. Deleted orders are ignored.
 */
@Service
public class CustomerQueryService {

    private static final Map<String, String> SORTS = Map.of(
            "lastOrder", "c.last_order desc, c.phone_key",
            "totalPaid", "c.total_paid desc, c.last_order desc",
            "orders", "c.orders desc, c.last_order desc",
            "name", "lower(l.name), c.phone_key");

    /** Per-customer aggregates joined with the contact details of their latest order. */
    private static final String BASE = """
            with c as (
                select customer_phone_key as phone_key,
                       count(*) as orders,
                       count(*) filter (where status = 'DELIVERED') as delivered,
                       count(*) filter (where status = 'RETURNED') as returned,
                       count(*) filter (where status = 'CANCELLED') as cancelled,
                       coalesce(sum(total) filter (where status = 'DELIVERED'), 0) as total_paid,
                       min(created_at) as first_order,
                       max(created_at) as last_order
                from orders
                where deleted_at is null
                group by customer_phone_key
            ),
            l as (
                select distinct on (customer_phone_key)
                       customer_phone_key as phone_key, customer_phone as phone, customer_name as name,
                       customer_email as email, customer_address as address
                from orders
                where deleted_at is null
                order by customer_phone_key, created_at desc, id desc
            )
            """;

    private static final RowMapper<CustomerRow> ROW = (rs, i) -> new CustomerRow(
            rs.getString("phone_key"), rs.getString("phone"), rs.getString("name"), rs.getString("email"),
            rs.getString("address"), rs.getLong("orders"), rs.getLong("delivered"), rs.getLong("returned"),
            rs.getLong("cancelled"), rs.getBigDecimal("total_paid"),
            rs.getTimestamp("first_order").toInstant(), rs.getTimestamp("last_order").toInstant());

    private final JdbcClient jdbc;

    CustomerQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CustomerPage search(CustomerQuery query, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = where(query, params);

        long total = jdbc.sql(BASE + "select count(*) from c join l on l.phone_key = c.phone_key " + where)
                .params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<CustomerRow> rows = jdbc.sql(BASE + "select c.*, l.phone, l.name, l.email, l.address "
                        + "from c join l on l.phone_key = c.phone_key " + where
                        + " order by " + order(query.sort()) + " limit :limit offset :offset")
                .params(params).query(ROW).list();

        return new CustomerPage(rows, page, size, total, (int) Math.ceil((double) total / size), summary());
    }

    /** Every matching customer, for the Excel export (capped by the caller). */
    @Transactional(readOnly = true)
    public List<CustomerRow> all(CustomerQuery query, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(query, params);
        params.put("limit", limit);
        return jdbc.sql(BASE + "select c.*, l.phone, l.name, l.email, l.address "
                        + "from c join l on l.phone_key = c.phone_key " + where
                        + " order by " + order(query.sort()) + " limit :limit")
                .params(params).query(ROW).list();
    }

    @Transactional(readOnly = true)
    public long count(CustomerQuery query) {
        Map<String, Object> params = new HashMap<>();
        String where = where(query, params);
        return jdbc.sql(BASE + "select count(*) from c join l on l.phone_key = c.phone_key " + where)
                .params(params).query(Long.class).single();
    }

    /** The customer with exactly this phone (any formatting), if they have ordered before. */
    @Transactional(readOnly = true)
    public Optional<CustomerRow> findByPhone(String phone) {
        String key = PhoneKeys.normalize(phone);
        if (key.length() < PhoneKeys.MIN_LOOKUP_DIGITS) {
            return Optional.empty();
        }
        return jdbc.sql(BASE + "select c.*, l.phone, l.name, l.email, l.address "
                        + "from c join l on l.phone_key = c.phone_key where c.phone_key = :key")
                .param("key", key).query(ROW).optional();
    }

    private CustomerSummary summary() {
        return jdbc.sql(BASE + """
                        select count(*) as customers,
                               count(*) filter (where orders >= 2) as repeat_customers,
                               count(*) filter (where returned > 0) as with_returns
                        from c
                        """)
                .query((rs, i) -> new CustomerSummary(rs.getLong("customers"), rs.getLong("repeat_customers"),
                        rs.getLong("with_returns")))
                .single();
    }

    /** Builds the WHERE clause from fixed fragments; user input only ever travels as bind parameters. */
    private static String where(CustomerQuery query, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("where true");
        String search = query.search() == null ? "" : query.search().trim();
        if (!search.isEmpty()) {
            params.put("text", "%" + search.toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            sql.append(" and (lower(l.name) like :text escape '\\' or lower(coalesce(l.email, '')) like :text escape '\\'"
                    + " or lower(l.address) like :text escape '\\'");
            String digits = PhoneKeys.normalize(search);
            if (digits.length() >= 3) {
                params.put("digits", "%" + digits + "%");
                sql.append(" or c.phone_key like :digits");
            }
            sql.append(")");
        }
        if (query.segment() != null && !query.segment().isBlank()) {
            switch (query.segment()) {
                case "repeat" -> sql.append(" and c.orders >= 2");
                case "returns" -> sql.append(" and c.returned > 0");
                default -> throw ApiException.badRequest("INVALID_SEGMENT", "segment must be repeat or returns");
            }
        }
        return sql.toString();
    }

    private static String order(String sort) {
        String order = SORTS.get(sort == null || sort.isBlank() ? "lastOrder" : sort);
        if (order == null) {
            throw ApiException.badRequest("INVALID_SORT", "Cannot sort by " + sort);
        }
        return order;
    }
}
