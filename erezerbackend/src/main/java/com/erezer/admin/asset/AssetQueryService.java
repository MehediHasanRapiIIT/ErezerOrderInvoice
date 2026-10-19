package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.AssetPage;
import com.erezer.admin.asset.dto.AssetDtos.AssetResponse;
import com.erezer.admin.asset.dto.AssetDtos.CategoryRef;
import com.erezer.admin.asset.dto.AssetDtos.EventView;
import com.erezer.admin.asset.dto.AssetDtos.FileRef;
import com.erezer.admin.asset.dto.AssetDtos.Summary;
import com.erezer.admin.asset.dto.AssetDtos.UserRef;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.file.FileService;
import com.erezer.admin.file.StoredFile;
import com.erezer.admin.user.UserDirectory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AssetQueryService {

    private final AssetRepository assets;
    private final AssetEventRepository events;
    private final UserDirectory userDirectory;
    private final FileService files;
    private final EntityManager entityManager;
    private final JdbcClient jdbc;

    AssetQueryService(AssetRepository assets, AssetEventRepository events, UserDirectory userDirectory,
            FileService files, EntityManager entityManager, JdbcClient jdbc) {
        this.assets = assets;
        this.events = events;
        this.userDirectory = userDirectory;
        this.files = files;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    public AssetPage search(AssetFilter filter, Pageable pageable) {
        Specification<Asset> spec = filter.toSpecification();
        Page<Asset> page = assets.findAll(spec, pageable);
        Map<Long, BigDecimal> repairs = repairTotals(page.getContent().stream().map(Asset::getId).toList());
        Map<Long, String> names = userDirectory.namesById(page.getContent().stream().map(Asset::getCreatedBy).toList());

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<Asset> root = query.from(Asset.class);
        Predicate where = spec.toPredicate(root, query, cb);
        query.multiselect(cb.coalesce(cb.sum(root.<BigDecimal>get("totalCost")), BigDecimal.ZERO),
                cb.coalesce(cb.sumAsLong(root.get("quantity")), 0L));
        if (where != null) {
            query.where(where);
        }
        Tuple totals = entityManager.createQuery(query).getSingleResult();

        return new AssetPage(page.getContent().stream().map(a -> toResponse(a, repairs, names, List.of())).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(),
                totals.get(0, BigDecimal.class), totals.get(1, Long.class), summary(filter.today()));
    }

    public AssetResponse get(long id) {
        Asset asset = assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset", id));
        List<AssetEvent> history = events.findByAssetIdOrderByEventDateDescIdDesc(id);
        List<Long> userIds = new ArrayList<>(history.stream().map(AssetEvent::getCreatedBy).toList());
        userIds.add(asset.getCreatedBy());
        Map<Long, String> names = userDirectory.namesById(userIds);
        List<EventView> views = history.stream()
                .map(e -> new EventView(e.getId(), e.getKind(), e.getEventDate(), e.getDescription(), e.getCost(),
                        user(e.getCreatedBy(), names), e.getCreatedAt()))
                .toList();
        return toResponse(asset, repairTotals(List.of(id)), names, views);
    }

    public List<String> locations() {
        return assets.locations();
    }

    /** Shop-wide figures for the strip above the list (filters don't apply). */
    private Summary summary(LocalDate today) {
        return jdbc.sql("""
                        select coalesce(sum(total_cost) filter (where status not in ('SOLD', 'DISPOSED')), 0),
                               coalesce(sum(quantity) filter (where status not in ('SOLD', 'DISPOSED')), 0),
                               count(*) filter (where status not in ('SOLD', 'DISPOSED')),
                               count(*) filter (where status = 'UNDER_REPAIR'),
                               count(*) filter (where status not in ('SOLD', 'DISPOSED')
                                                  and warranty_until between :today and :soon),
                               (select coalesce(sum(e.cost), 0) from asset_events e join assets x on x.id = e.asset_id
                                where e.kind = 'REPAIR' and x.deleted_at is null)
                        from assets where deleted_at is null
                        """)
                .param("today", Date.valueOf(today))
                .param("soon", Date.valueOf(today.plusDays(AssetFilter.WARRANTY_SOON_DAYS)))
                .query((rs, i) -> new Summary(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getBigDecimal(6)))
                .single();
    }

    private Map<Long, BigDecimal> repairTotals(List<Long> ids) {
        Map<Long, BigDecimal> totals = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : events.repairTotals(ids)) {
                totals.put((Long) row[0], (BigDecimal) row[1]);
            }
        }
        return totals;
    }

    private AssetResponse toResponse(Asset a, Map<Long, BigDecimal> repairs, Map<Long, String> names,
            List<EventView> history) {
        return new AssetResponse(a.getId(), a.getName(), new CategoryRef(a.getCategory().getId(), a.getCategory().getName()),
                a.getQuantity(), a.getUnitCost(), a.getTotalCost(), a.getPurchaseDate(), a.getSupplier(),
                a.getPaymentMethod(), a.getReference(), a.getLocation(), a.getSerialNumber(), a.getWarrantyUntil(),
                a.getStatus(), a.getStatus().label(), a.getDisposedOn(), a.getSalePrice(), a.getNotes(),
                file(a.getPhoto()), file(a.getReceipt()), repairs.getOrDefault(a.getId(), BigDecimal.ZERO),
                user(a.getCreatedBy(), names), a.getCreatedAt(), a.getUpdatedAt(), a.getVersion(), history);
    }

    private FileRef file(StoredFile file) {
        return file == null ? null : new FileRef(file.getId(), files.url(file), file.getContentType(), file.getOriginalName());
    }

    private static UserRef user(Long id, Map<Long, String> names) {
        return id == null ? null : new UserRef(id, UserDirectory.nameOf(names, id));
    }
}
