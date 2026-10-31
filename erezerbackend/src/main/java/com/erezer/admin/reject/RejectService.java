package com.erezer.admin.reject;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.common.web.PageResponse;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.reject.Reject.Kind;
import com.erezer.admin.reject.RejectDtos.RejectRequest;
import com.erezer.admin.reject.RejectDtos.RejectView;
import com.erezer.admin.reject.RejectDtos.Summary;
import com.erezer.admin.stock.StockService;
import com.erezer.admin.user.UserDirectory;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rejected pieces. Picking a stock variant takes them out of stock; "Other" is only a typed name and moves nothing.
 * Editing or deleting a reject corrects a mistake, so what it took from stock goes back.
 */
@Service
public class RejectService {

    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2999, 12, 31);

    private final RejectRepository rejects;
    private final StockService stock;
    private final UserDirectory userDirectory;
    private final JdbcClient jdbc;
    private final AppProperties appProperties;
    private final Clock clock;

    RejectService(RejectRepository rejects, StockService stock, UserDirectory userDirectory, JdbcClient jdbc,
            AppProperties appProperties, Clock clock) {
        this.rejects = rejects;
        this.stock = stock;
        this.userDirectory = userDirectory;
        this.jdbc = jdbc;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public RejectView create(RejectRequest request, long userId) {
        requireNotFuture(request.date());
        Reject.Details details = details(request);
        take(details, userId);
        return toViews(List.of(rejects.saveAndFlush(new Reject(details)))).getFirst();
    }

    @Transactional
    public RejectView update(long id, RejectRequest request, long userId) {
        Reject reject = require(id);
        if (request.version() == null || request.version() != reject.getVersion()) {
            throw ApiException.conflict("STALE_DATA", "This reject was changed by someone else. Reload and try again.");
        }
        requireNotFuture(request.date());
        Reject.Details details = details(request);
        giveBack(reject, userId);
        take(details, userId);
        reject.apply(details);
        return toViews(List.of(rejects.saveAndFlush(reject))).getFirst();
    }

    @Transactional
    public void delete(long id, long userId) {
        Reject reject = require(id);
        giveBack(reject, userId);
        rejects.delete(reject);
    }

    private void take(Reject.Details d, long userId) {
        if (d.stockItemId() != null) {
            stock.takeForReject(d.variantId(), d.stockItemId(), d.quantity(), userId);
        }
    }

    private void giveBack(Reject reject, long userId) {
        if (reject.isFromStock()) {
            stock.giveBackReject(reject.getVariantId(), reject.getStockItemId(), reject.getQuantity(), userId);
        }
    }

    private Reject.Details details(RejectRequest r) {
        String note = blankToNull(r.note());
        if (r.variantId() == null) {
            String name = blankToNull(r.otherName());
            if (name == null) {
                throw ApiException.badRequest("OTHER_NAME_REQUIRED", "Write the variant name");
            }
            return new Reject.Details(r.date(), null, null, name, blankToNull(r.size()), r.kind(), r.quantity(), note);
        }
        StockService.StockPick pick = stock.pick(r.variantId(), r.stockItemId());
        return new Reject.Details(r.date(), pick.variantId(), pick.stockItemId(), pick.variantName(), pick.size(), r.kind(), r.quantity(), note);
    }

    @Transactional(readOnly = true)
    public PageResponse<RejectView> list(LocalDate from, LocalDate to, Kind kind, Boolean fromStock, String q, Pageable pageable) {
        Page<Reject> page = rejects.search(start(from), end(to), kind == null ? EnumSet.allOf(Kind.class) : Set.of(kind),
                fromStock == null ? Set.of(true, false) : Set.of(fromStock), like(q), pageable);
        return new PageResponse<>(toViews(page.getContent()), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to, String q) {
        return jdbc.sql("""
                        select coalesce(sum(quantity), 0), count(*),
                               coalesce(sum(quantity) filter (where kind = 'SOLID'), 0),
                               coalesce(sum(quantity) filter (where kind = 'PRINTED'), 0),
                               coalesce(sum(quantity) filter (where from_stock), 0),
                               coalesce(sum(quantity) filter (where not from_stock), 0)
                        from rejects
                        where reject_date between :from and :to
                          and (lower(variant_name) like :like or lower(coalesce(note, '')) like :like)
                        """)
                .param("from", java.sql.Date.valueOf(start(from))).param("to", java.sql.Date.valueOf(end(to))).param("like", like(q))
                .query((rs, i) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)))
                .single();
    }

    private Reject require(long id) {
        return rejects.findById(id).orElseThrow(() -> ApiException.notFound("Reject", id));
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

    private List<RejectView> toViews(List<Reject> list) {
        Map<Long, String> names = userDirectory.namesById(list.stream().map(Reject::getCreatedBy).filter(Objects::nonNull).toList());
        return list.stream().map(r -> new RejectView(r.getId(), r.getRejectDate(), r.isFromStock(), r.getVariantId(), r.getStockItemId(),
                r.getVariantName(), r.getSize(), r.getKind(), r.getQuantity(), r.getNote(),
                r.getCreatedBy() == null ? null : UserDirectory.nameOf(names, r.getCreatedBy()), r.getCreatedAt(), r.getVersion())).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
