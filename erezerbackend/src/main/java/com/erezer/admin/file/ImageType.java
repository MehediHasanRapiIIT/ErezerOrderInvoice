package com.erezer.admin.file;

import java.util.Optional;

/**
 * Image formats accepted for upload, detected from the file's magic bytes rather than the
 * client-supplied content type or extension.
 */
enum ImageType {

    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    /** Bytes needed to recognise every supported format. */
    static final int HEADER_LENGTH = 12;

    private final String contentType;
    private final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    String contentType() {
        return contentType;
    }

    String extension() {
        return extension;
    }

    static Optional<ImageType> detect(byte[] header) {
        if (header.length >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (header.length >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return Optional.of(PNG);
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }
}
