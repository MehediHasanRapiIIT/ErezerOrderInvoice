package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.AssetPage;
import com.erezer.admin.asset.dto.AssetDtos.AssetRequest;
import com.erezer.admin.asset.dto.AssetDtos.AssetResponse;
import com.erezer.admin.asset.dto.AssetDtos.RepairRequest;
import com.erezer.admin.asset.dto.AssetDtos.StatusRequest;
import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.config.AppProperties;
import com.erezer.admin.security.CurrentUser;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assets")
class AssetController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Map<String, String> SORTABLE = Map.of(
            "purchaseDate", "purchaseDate",
            "totalCost", "totalCost",
            "name", "name",
            "warrantyUntil", "warrantyUntil",
            "createdAt", "createdAt");
    private static final Set<String> SCOPES = Set.of("OWNED", "ALL");

    private final AssetService assetService;
    private final AssetQueryService queryService;
    private final AssetExportService exportService;
    private final AppProperties appProperties;

    AssetController(AssetService assetService, AssetQueryService queryService, AssetExportService exportService,
            AppProperties appProperties) {
        this.assetService = assetService;
        this.queryService = queryService;
        this.exportService = exportService;
        this.appProperties = appProperties;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    AssetPage list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String warranty,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "purchaseDate,desc") String sort) {
        if ("totalCost".equals(sort.split(",")[0]) && !AssetVisibility.costs()) {
            sort = "purchaseDate,desc"; // sorting by an amount you can't see would give it away
        }
        return AssetVisibility.trim(queryService.search(filter(q, categoryId, status, location, from, to, warranty),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), parseSort(sort))));
    }

    @GetMapping("/export.xlsx")
    @PreAuthorize("hasAuthority('ASSET_EXPORT')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String warranty,
            @RequestParam(defaultValue = "purchaseDate,desc") String sort) {
        byte[] workbook = exportService.export(filter(q, categoryId, status, location, from, to, warranty), parseSort(sort),
                AssetVisibility.costs());
        String fileName = "assets-" + LocalDate.now(appProperties.zoneId()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(workbook);
    }

    /** Locations already used, for the form's suggestions and the filter. */
    @GetMapping("/locations")
    @PreAuthorize("hasAnyAuthority('ASSET_VIEW', 'ASSET_CREATE', 'ASSET_MANAGE')")
    List<String> locations() {
        return queryService.locations();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_VIEW')")
    AssetResponse get(@PathVariable long id) {
        return AssetVisibility.trim(queryService.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ASSET_CREATE')")
    ResponseEntity<AssetResponse> create(@Valid @RequestBody AssetRequest request) {
        long id = assetService.create(request, CurrentUser.id(), AssetVisibility.costs());
        return ResponseEntity.created(URI.create("/api/assets/" + id)).body(AssetVisibility.trim(queryService.get(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_MANAGE')")
    AssetResponse update(@PathVariable long id, @Valid @RequestBody AssetRequest request) {
        assetService.update(id, request, CurrentUser.id(), AssetVisibility.costs());
        return AssetVisibility.trim(queryService.get(id));
    }

    /** In use, under repair, in storage, damaged, sold (with the price) or disposed. */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ASSET_STATUS')")
    AssetResponse changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        assetService.changeStatus(id, request, CurrentUser.id(), AssetVisibility.costs());
        return AssetVisibility.trim(queryService.get(id));
    }

    @PostMapping("/{id}/repairs")
    @PreAuthorize("hasAuthority('ASSET_REPAIRS')")
    AssetResponse addRepair(@PathVariable long id, @Valid @RequestBody RepairRequest request) {
        assetService.addRepair(id, request, CurrentUser.id());
        return AssetVisibility.trim(queryService.get(id));
    }

    @DeleteMapping("/{id}/repairs/{eventId}")
    @PreAuthorize("hasAuthority('ASSET_REPAIRS')")
    AssetResponse deleteRepair(@PathVariable long id, @PathVariable long eventId) {
        assetService.deleteRepair(id, eventId);
        return AssetVisibility.trim(queryService.get(id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        assetService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private AssetFilter filter(String q, Long categoryId, String status, String location, LocalDate from,
            LocalDate to, String warranty) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "The start date must be on or before the end date");
        }
        if (status != null && !status.isBlank() && !SCOPES.contains(status)
                && Arrays.stream(AssetStatus.values()).noneMatch(s -> s.name().equals(status))) {
            throw ApiException.badRequest("INVALID_STATUS", "Unknown status " + status);
        }
        if (warranty != null && !warranty.isBlank() && !Set.of("SOON", "EXPIRED").contains(warranty)) {
            throw ApiException.badRequest("INVALID_WARRANTY_FILTER", "Unknown warranty filter " + warranty);
        }
        return new AssetFilter(q, categoryId, status, location, from, to, warranty, LocalDate.now(appProperties.zoneId()));
    }

    private static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String property = SORTABLE.get(parts[0].trim());
        if (property == null) {
            throw ApiException.badRequest("INVALID_SORT", "Cannot sort by " + parts[0]);
        }
        boolean ascending = parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc");
        Sort primary = ascending ? Sort.by(property).ascending() : Sort.by(property).descending();
        return primary.and(Sort.by("id").descending());
    }
}
