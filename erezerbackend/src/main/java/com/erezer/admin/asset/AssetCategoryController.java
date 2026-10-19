package com.erezer.admin.asset;

import com.erezer.admin.asset.dto.AssetDtos.CategoryRequest;
import com.erezer.admin.asset.dto.AssetDtos.CategoryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/asset-categories")
class AssetCategoryController {

    private final AssetCategoryService categoryService;

    AssetCategoryController(AssetCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** All categories (active and inactive) with how many assets use each. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('ASSET_VIEW', 'ASSET_MANAGE')")
    List<CategoryResponse> list() {
        return categoryService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ASSET_MANAGE')")
    CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    /** Rename and/or activate/deactivate. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_MANAGE')")
    ResponseEntity<Void> update(@PathVariable long id, @Valid @RequestBody CategoryRequest request) {
        categoryService.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ASSET_DELETE')")
    ResponseEntity<Void> delete(@PathVariable long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
