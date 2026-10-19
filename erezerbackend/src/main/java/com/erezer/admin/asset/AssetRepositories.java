package com.erezer.admin.asset;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    @Query("""
            select distinct a.location from Asset a
            where a.location is not null
            order by a.location
            """)
    List<String> locations();
}

interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {

    List<AssetCategory> findAllByOrderByNameAsc();

    @Query("select count(c) > 0 from AssetCategory c where lower(c.name) = lower(:name) and c.id <> :excludeId")
    boolean nameTaken(@Param("name") String name, @Param("excludeId") long excludeId);
}

interface AssetEventRepository extends JpaRepository<AssetEvent, Long> {

    List<AssetEvent> findByAssetIdOrderByEventDateDescIdDesc(long assetId);

    /** Repair spending per asset: rows of [assetId, total]. */
    @Query("""
            select e.assetId, coalesce(sum(e.cost), 0) from AssetEvent e
            where e.kind = com.erezer.admin.asset.AssetEventKind.REPAIR and e.assetId in :ids
            group by e.assetId
            """)
    List<Object[]> repairTotals(@Param("ids") Collection<Long> ids);
}
