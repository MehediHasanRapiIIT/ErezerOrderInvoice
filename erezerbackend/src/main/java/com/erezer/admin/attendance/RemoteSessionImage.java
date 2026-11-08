package com.erezer.admin.attendance;

import com.erezer.admin.file.StoredFile;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of a remote session's image, so the nightly clean-up of unused files knows the image is in use.
 * Sessions themselves are written by {@link RemoteService}.
 */
@Entity
@Immutable
@Table(name = "remote_sessions")
public class RemoteSessionImage {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id")
    private StoredFile image;

    protected RemoteSessionImage() {
        // for JPA
    }
}
