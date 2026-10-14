package com.erezer.admin.file;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.storage.StorageService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    public static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    public static final int MAX_FILES_PER_UPLOAD = 10;
    /** Uploads must be attached to a record within this window or they are deleted. */
    private static final Duration ORPHAN_GRACE = Duration.ofHours(24);

    private final StoredFileRepository files;
    private final StorageService storage;
    private final Clock clock;

    FileService(StoredFileRepository files, StorageService storage, Clock clock) {
        this.files = files;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public List<StoredFile> uploadImages(List<MultipartFile> uploads, long uploadedBy) {
        if (uploads == null || uploads.isEmpty()) {
            throw ApiException.badRequest("NO_FILES", "Select at least one image");
        }
        if (uploads.size() > MAX_FILES_PER_UPLOAD) {
            throw ApiException.badRequest("TOO_MANY_FILES", "Upload at most " + MAX_FILES_PER_UPLOAD + " images at a time");
        }
        List<StoredFile> stored = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            stored.add(storeImage(upload, uploadedBy));
        }
        return stored;
    }

    /** Loads files by id, failing if any is missing or is not an image. */
    @Transactional(readOnly = true)
    public Map<Long, StoredFile> requireImages(Collection<Long> ids) {
        Map<Long, StoredFile> found = files.findAllById(ids).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        for (Long id : ids) {
            StoredFile file = found.get(id);
            if (file == null || !file.isImage()) {
                throw ApiException.badRequest("INVALID_IMAGE", "Image " + id + " does not exist. Upload it again.");
            }
        }
        return found;
    }

    public String url(StoredFile file) {
        return storage.presignedGetUrl(file.getObjectKey()).toString();
    }

    /** Deletes uploads that were never attached (abandoned forms) or were detached by an edit. */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.time-zone}")
    @Transactional
    public void purgeOrphans() {
        List<StoredFile> orphans = files.findUnreferencedBefore(clock.instant().minus(ORPHAN_GRACE), Limit.of(500));
        for (StoredFile file : orphans) {
            try {
                storage.delete(file.getObjectKey());
                files.delete(file);
            } catch (RuntimeException ex) {
                log.warn("Could not delete orphan file {}", file.getObjectKey(), ex);
            }
        }
        if (!orphans.isEmpty()) {
            log.info("Purged {} orphan files", orphans.size());
        }
    }

    private StoredFile storeImage(MultipartFile upload, long uploadedBy) {
        if (upload.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "One of the files is empty");
        }
        if (upload.getSize() > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Images must be 10 MB or smaller");
        }
        try (InputStream in = new BufferedInputStream(upload.getInputStream())) {
            in.mark(ImageType.HEADER_LENGTH);
            byte[] header = in.readNBytes(ImageType.HEADER_LENGTH);
            in.reset();
            ImageType type = ImageType.detect(header).orElseThrow(() -> ApiException.badRequest(
                    "UNSUPPORTED_IMAGE", "Only JPEG, PNG and WebP images are supported"));

            Instant now = clock.instant();
            ZonedDateTime date = now.atZone(ZoneOffset.UTC);
            String key = "images/%d/%02d/%s.%s".formatted(date.getYear(), date.getMonthValue(), UUID.randomUUID(),
                    type.extension());
            storage.put(key, in, upload.getSize(), type.contentType());
            return files.save(new StoredFile(key, type.contentType(), upload.getSize(),
                    truncate(upload.getOriginalFilename(), 255), uploadedBy, now));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read uploaded file", ex);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
