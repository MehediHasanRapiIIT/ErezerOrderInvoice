package com.erezer.admin.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Metadata of a binary held in object storage. */
@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "original_name", length = 255)
    private String originalName;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StoredFile() {
        // for JPA
    }

    StoredFile(String objectKey, String contentType, long sizeBytes, String originalName, Long uploadedBy,
            Instant createdAt) {
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.originalName = originalName;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getOriginalName() {
        return originalName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isImage() {
        return contentType.startsWith("image/");
    }
}
