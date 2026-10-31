package com.erezer.admin.boost;

import com.erezer.admin.boost.BoostDtos.CostRequest;
import com.erezer.admin.boost.BoostDtos.CostView;
import com.erezer.admin.boost.BoostDtos.PurchaseLine;
import com.erezer.admin.boost.BoostDtos.PurchaseRequest;
import com.erezer.admin.boost.BoostDtos.PurchaseView;
import com.erezer.admin.boost.BoostDtos.Summary;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.user.UserDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ad boosting: the dollars spent each day, turned into taka at the rate given, and the dollars bought to pay for it.
 * Kept apart from the Expenses menu: nothing here changes its totals, profit or the dashboard.
 */
@Service
public class BoostService {

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);

    private final BoostCostRepository costs;
    private final BoostPurchaseRepository purchases;
    private final UserDirectory userDirectory;
    private final JdbcClient jdbc;
    private final AppProperties appProperties;
    private final Clock clock;

    BoostService(BoostCostRepository costs, BoostPurchaseRepository purchases, UserDirectory userDirectory, JdbcClient jdbc,
            AppProperties appProperties, Clock clock) {
        this.costs = costs;
        this.purchases = purchases;
        this.userDirectory = userDirectory;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public CostView addCost(CostRequest request) {
        requireNotFuture(request.date());
        BoostCost cost = costs.saveAndFlush(new BoostCost(request.date(), request.usd(), request.rate(), blankToNull(request.note())));
        return toCostViews(List.of(cost)).getFirst();
    }

    @Transactional
    public PurchaseView addPurchase(PurchaseRequest request) {
        requireNotFuture(request.date());
        BoostPurchase purchase = purchases.saveAndFlush(new BoostPurchase(request.date(), lines(request), blankToNull(request.note())));
        return toPurchaseViews(List.of(purchase)).getFirst();
    }

    /** Only this entry changes; other days keep the rate they were saved with. */
    @Transactional
    public CostView updateCost(long id, CostRequest request) {
        BoostCost cost = costs.findById(id).orElseThrow(() -> ApiException.notFound("Boost cost", id));
        requireCurrent(request.version(), cost.getVersion());
        requireNotFuture(request.date());
        cost.update(request.date(), request.usd(), request.rate(), blankToNull(request.note()));
        return toCostViews(List.of(costs.saveAndFlush(cost))).getFirst();
    }

    @Transactional
    public PurchaseView updatePurchase(long id, PurchaseRequest request) {
        BoostPurchase purchase = purchases.findById(id).orElseThrow(() -> ApiException.notFound("Boost purchase", id));
        requireCurrent(request.version(), purchase.getVersion());
        requireNotFuture(request.date());
        purchase.update(request.date(), lines(request), blankToNull(request.note()));
        return toPurchaseViews(List.of(purchases.saveAndFlush(purchase))).getFirst();
    }

    private static List<BoostPurchase.Line> lines(PurchaseRequest request) {
        return request.lines().stream().map(l -> new BoostPurchase.Line(l.usd(), l.bdt())).toList();
    }

    private static void requireCurrent(Long sent, long current) {
        if (sent == null || sent != current) {
            throw ApiException.conflict("STALE_DATA", "This entry was changed by someone else. Reload and try again.");
        }
    }

    @Transactional
    public void deleteCost(long id) {
        costs.delete(costs.findById(id).orElseThrow(() -> ApiException.notFound("Boost cost", id)));
    }

    @Transactional
    public void deletePurchase(long id) {
        purchases.delete(purchases.findById(id).orElseThrow(() -> ApiException.notFound("Boost purchase", id)));
    }

    @Transactional(readOnly = true)
    public PageResponse<CostView> costs(LocalDate from, LocalDate to, String q, Pageable pageable) {
        Page<BoostCost> page = costs.search(start(from), end(to), like(q), pageable);
        return new PageResponse<>(toCostViews(page.getContent()), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseView> purchases(LocalDate from, LocalDate to, String q, Pageable pageable) {
        Page<BoostPurchase> page = purchases.search(start(from), end(to), like(q), pageable);
        return new PageResponse<>(toPurchaseViews(page.getContent()), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** @param withPurchases they may see what dollars cost */
    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to, boolean withPurchases) {
        Map<String, Object> range = Map.of("from", java.sql.Date.valueOf(start(from)), "to", java.sql.Date.valueOf(end(to)));
        record Spent(BigDecimal usd, BigDecimal bdt, long count) {
        }
        Spent spent = jdbc.sql("""
                        select coalesce(sum(usd), 0), coalesce(sum(bdt), 0), count(*)
                        from boost_costs where cost_date between :from and :to
                        """)
                .params(range)
                .query((rs, i) -> new Spent(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getLong(3))).single();
        if (!withPurchases) {
            return new Summary(spent.usd(), spent.bdt(), spent.count(), null, null, null, null);
        }
        record Bought(BigDecimal usd, BigDecimal bdt) {
        }
        Bought bought = jdbc.sql("""
                        select coalesce(sum(usd), 0), coalesce(sum(bdt), 0)
                        from boost_purchases where purchase_date between :from and :to
                        """)
                .params(range)
                .query((rs, i) -> new Bought(rs.getBigDecimal(1), rs.getBigDecimal(2))).single();
        BigDecimal allSpent = jdbc.sql("select coalesce(sum(usd), 0) from boost_costs").query(BigDecimal.class).single();
        BigDecimal allBought = jdbc.sql("select coalesce(sum(usd), 0) from boost_purchases").query(BigDecimal.class).single();
        return new Summary(spent.usd(), spent.bdt(), spent.count(), bought.usd(), bought.bdt(), averageRate(), allBought.subtract(allSpent));
    }

    /** What a dollar has cost on average; null until something was bought. */
    @Transactional(readOnly = true)
    public BigDecimal averageRate() {
        return jdbc.sql("select sum(bdt), sum(usd) from boost_purchases")
                .query((rs, i) -> rs.getBigDecimal(2) == null ? null : rs.getBigDecimal(1).divide(rs.getBigDecimal(2), 2, RoundingMode.HALF_UP))
                .optional().orElse(null);
    }

    private void requireNotFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock.withZone(appProperties.zoneId())))) {
            throw ApiException.badRequest("FUTURE_DATE", "The date can't be in the future");
        }
    }

    private static LocalDate start(LocalDate from) {
        return from == null ? EARLIEST : from;
    }

    private static LocalDate end(LocalDate to) {
        return to == null ? LATEST : to;
    }

    private static String like(String q) {
        return "%" + (q == null ? "" : q.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private List<CostView> toCostViews(List<BoostCost> list) {
        Map<Long, String> names = userDirectory.namesById(new ArrayList<>(list.stream().map(BoostCost::getCreatedBy).filter(Objects::nonNull).toList()));
        return list.stream().map(c -> new CostView(c.getId(), c.getCostDate(), c.getUsd(), c.getRate(), c.getBdt(), c.getNote(),
                c.getCreatedBy() == null ? null : UserDirectory.nameOf(names, c.getCreatedBy()), c.getCreatedAt(), c.getVersion())).toList();
    }

    private List<PurchaseView> toPurchaseViews(List<BoostPurchase> list) {
        Map<Long, String> names = userDirectory.namesById(new ArrayList<>(list.stream().map(BoostPurchase::getCreatedBy).filter(Objects::nonNull).toList()));
        return list.stream().map(p -> new PurchaseView(p.getId(), p.getPurchaseDate(), p.getUsd(), p.getBdt(), p.getRate(),
                p.getLines().stream().map(l -> new PurchaseLine(l.getUsd(), l.getBdt())).toList(), p.getNote(),
                p.getCreatedBy() == null ? null : UserDirectory.nameOf(names, p.getCreatedBy()), p.getCreatedAt(), p.getVersion())).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
