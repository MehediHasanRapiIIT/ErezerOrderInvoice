package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.CategoryRequest;
import com.erezer.admin.asset.dto.AssetDtos.CategoryResponse;
import com.erezer.admin.common.error.ApiException;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Asset categories, managed like expense categories but kept separate from them. */
@Service
public class AssetCategoryService {

    private final AssetCategoryRepository categories;
    private final EntityManager entityManager;

    AssetCategoryService(AssetCategoryRepository categories, EntityManager entityManager) {
        this.categories = categories;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        Map<Long, Long> counts = assetCounts();
        return categories.findAllByOrderByNameAsc().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName(), c.isActive(), counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryRequest request) {
        String name = normalize(request.name());
        if (categories.nameTaken(name, 0L)) {
            throw ApiException.conflict("CATEGORY_EXISTS", "A category named " + name + " already exists");
        }
        AssetCategory category = categories.save(new AssetCategory(name));
        return new CategoryResponse(category.getId(), category.getName(), true, 0);
    }

    @Transactional
    public void update(long id, CategoryRequest request) {
        AssetCategory category = load(id);
        String name = normalize(request.name());
        if (categories.nameTaken(name, id)) {
            throw ApiException.conflict("CATEGORY_EXISTS", "A category named " + name + " already exists");
        }
        category.rename(name);
        if (request.active() != null) {
            category.setActive(request.active());
        }
    }

    /** Only never-used categories can be deleted; used ones are deactivated to keep history intact. */
    @Transactional
    public void delete(long id) {
        AssetCategory category = load(id);
        Number used = (Number) entityManager
                .createNativeQuery("select count(*) from assets where category_id = :id")
                .setParameter("id", id)
                .getSingleResult();
        if (used.longValue() > 0) {
            throw ApiException.conflict("CATEGORY_IN_USE",
                    "This category has assets. Deactivate it instead so past records keep their category.");
        }
        categories.delete(category);
    }

    private Map<Long, Long> assetCounts() {
        List<?> rows = entityManager
                .createNativeQuery("select category_id, count(*) from assets where deleted_at is null group by category_id")
                .getResultList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object row : rows) {
            Object[] cols = (Object[]) row;
            counts.put(((Number) cols[0]).longValue(), ((Number) cols[1]).longValue());
        }
        return counts;
    }

    private AssetCategory load(long id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Category", id));
    }

    private static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ");
    }
}
