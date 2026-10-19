package com.erezer.admin.asset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** One line of an asset's history. */
@Entity
@Table(name = "asset_events")
public class AssetEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private AssetEventKind kind;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "cost", precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AssetEvent() {
        // for JPA
    }

    AssetEvent(long assetId, AssetEventKind kind, LocalDate eventDate, String description, BigDecimal cost,
            Long createdBy, Instant now) {
        this.assetId = assetId;
        this.kind = kind;
        this.eventDate = eventDate;
        this.description = description.length() > 500 ? description.substring(0, 497) + "..." : description;
        this.cost = cost == null ? null : cost.setScale(2, RoundingMode.HALF_UP);
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getAssetId() {
        return assetId;
    }

    public AssetEventKind getKind() {
        return kind;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
