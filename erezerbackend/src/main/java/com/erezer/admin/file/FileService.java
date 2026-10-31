package com.erezer.admin.file;

import com.erezer.admin.common.error.ApiException;
import com.erezer.admin.security.CurrentUser;
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
import java.util.Objects;
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
    public static final String PDF_TYPE = "application/pdf";
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
            stored.add(store(upload, uploadedBy, "images", false));
        }
        return stored;
    }

    /** A bill or voucher for an expense: a photo (JPEG/PNG/WebP) or a PDF. */
    @Transactional
    public StoredFile uploadReceipt(MultipartFile upload, long uploadedBy) {
        if (upload == null) {
            throw ApiException.badRequest("NO_FILES", "Select a file");
        }
        return store(upload, uploadedBy, "receipts", true);
    }

    /** An employee's photo, NID or ID card image: private, and only ever shown through a short-lived link. */
    @Transactional
    public StoredFile uploadEmployeeImage(MultipartFile upload, long uploadedBy) {
        if (upload == null) {
            throw ApiException.badRequest("NO_FILES", "Select an image");
        }
        return store(upload, uploadedBy, "employee-images", false);
    }

    /**
     * A photo, NID or ID card image for an employee record.
     *
     * @param current the record's image now, or null
     */
    @Transactional(readOnly = true)
    public StoredFile requireEmployeeImage(long id, Long current) {
        StoredFile file = files.findById(id).filter(StoredFile::isImage).filter(f -> f.getObjectKey().startsWith("employee-images/"))
                .orElseThrow(() -> ApiException.badRequest("INVALID_IMAGE", "The image no longer exists. Upload it again."));
        requireMine(file, current != null && current == id);
        return file;
    }

    /** A bill for a daily expense: a photo or a PDF. */
    @Transactional
    public StoredFile uploadDailyExpenseReceipt(MultipartFile upload, long uploadedBy) {
        if (upload == null) {
            throw ApiException.badRequest("NO_FILES", "Select a file");
        }
        return store(upload, uploadedBy, "daily-expenses", true);
    }

    /** The bill on a new daily expense: the person's own, uploaded for this. */
    @Transactional(readOnly = true)
    public StoredFile requireDailyExpenseReceipt(long id) {
        StoredFile file = files.findById(id).filter(f -> f.getObjectKey().startsWith("daily-expenses/"))
                .orElseThrow(() -> ApiException.badRequest("INVALID_RECEIPT", "The bill no longer exists. Upload it again."));
        requireMine(file, false);
        return file;
    }

    /** A photo taken at check-in. */
    @Transactional
    public StoredFile uploadSelfie(MultipartFile upload, long uploadedBy) {
        if (upload == null) {
            throw ApiException.badRequest("NO_FILES", "Take a photo first");
        }
        return store(upload, uploadedBy, "selfies", false);
    }

    /** The check-in photo: the employee's own, uploaded as a selfie. */
    @Transactional(readOnly = true)
    public StoredFile requireSelfie(long id) {
        StoredFile file = files.findById(id).filter(StoredFile::isImage).filter(f -> f.getObjectKey().startsWith("selfies/"))
                .orElseThrow(() -> ApiException.badRequest("INVALID_IMAGE", "The photo no longer exists. Take it again."));
        requireMine(file, false);
        return file;
    }

    /*
     * Attaching files. File ids are plain numbers, so a file is only accepted when it is the right kind (a product
     * image, or a receipt-upload) and either the current user uploaded it or the record already has it. Otherwise
     * anyone who can save an order could attach someone else's receipt photo by its number and then view it.
     */

    /**
     * An expense or asset receipt (photo or PDF) uploaded through the receipt upload.
     *
     * @param current the record's receipt now (kept even if someone else uploaded it), or null
     */
    @Transactional(readOnly = true)
    public StoredFile requireReceipt(long id, Long current) {
        StoredFile file = files.findById(id).filter(f -> f.isImage() || f.getContentType().equals(PDF_TYPE))
                .filter(f -> f.getObjectKey().startsWith("receipts/"))
                .orElseThrow(() -> ApiException.badRequest("INVALID_RECEIPT", "The receipt no longer exists. Upload it again."));
        requireMine(file, current != null && current == id);
        return file;
    }

    /**
     * An asset photo (an image uploaded through the receipt upload).
     *
     * @param current the asset's photo now, or null
     */
    @Transactional(readOnly = true)
    public StoredFile requirePhoto(long id, Long current) {
        StoredFile file = files.findById(id).filter(StoredFile::isImage).filter(f -> f.getObjectKey().startsWith("receipts/"))
                .orElseThrow(() -> ApiException.badRequest("INVALID_IMAGE", "The photo no longer exists. Upload it again."));
        requireMine(file, current != null && current == id);
        return file;
    }

    /**
     * Product images for order items.
     *
     * @param alreadyAttached images the record (or the order being exchanged) already has; they may stay
     */
    @Transactional(readOnly = true)
    public Map<Long, StoredFile> requireImages(Collection<Long> ids, Collection<Long> alreadyAttached) {
        Map<Long, StoredFile> found = files.findAllById(ids).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        for (Long id : ids) {
            StoredFile file = found.get(id);
            if (file == null || !file.isImage() || !file.getObjectKey().startsWith("images/")) {
                throw ApiException.badRequest("INVALID_IMAGE", "Image " + id + " does not exist. Upload it again.");
            }
            requireMine(file, alreadyAttached.contains(id));
        }
        return found;
    }

    private static void requireMine(StoredFile file, boolean alreadyOnRecord) {
        if (!alreadyOnRecord && !Objects.equals(file.getUploadedBy(), CurrentUser.id())) {
            throw ApiException.badRequest("FILE_NOT_YOURS", "A file can only be attached by the person who uploaded it. Upload it again.");
        }
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

    /**
     * Stores an upload after checking its real type from the magic bytes (never the client's claim).
     *
     * @param allowPdf also accept PDF documents (receipts)
     */
    private StoredFile store(MultipartFile upload, long uploadedBy, String folder, boolean allowPdf) {
        if (upload.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "One of the files is empty");
        }
        if (upload.getSize() > MAX_IMAGE_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Files must be 10 MB or smaller");
        }
        try (InputStream in = new BufferedInputStream(upload.getInputStream())) {
            in.mark(ImageType.HEADER_LENGTH);
            byte[] header = in.readNBytes(ImageType.HEADER_LENGTH);
            in.reset();

            String contentType;
            String extension;
            var image = ImageType.detect(header);
            if (image.isPresent()) {
                contentType = image.get().contentType();
                extension = image.get().extension();
            } else if (allowPdf && isPdf(header)) {
                contentType = PDF_TYPE;
                extension = "pdf";
            } else {
                throw allowPdf
                        ? ApiException.badRequest("UNSUPPORTED_FILE", "Only JPEG, PNG, WebP images or PDF files are supported")
                        : ApiException.badRequest("UNSUPPORTED_IMAGE", "Only JPEG, PNG and WebP images are supported");
            }

            Instant now = clock.instant();
            ZonedDateTime date = now.atZone(ZoneOffset.UTC);
            String key = "%s/%d/%02d/%s.%s".formatted(folder, date.getYear(), date.getMonthValue(), UUID.randomUUID(),
                    extension);
            storage.put(key, in, upload.getSize(), contentType);
            return files.save(new StoredFile(key, contentType, upload.getSize(),
                    truncate(upload.getOriginalFilename(), 255), uploadedBy, now));
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read uploaded file", ex);
        }
    }

    private static boolean isPdf(byte[] header) {
        return header.length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F'
                && header[4] == '-';
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
