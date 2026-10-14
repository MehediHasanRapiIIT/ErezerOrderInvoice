package com.erezer.admin.storage;

import java.io.InputStream;
import java.net.URL;

/**
 * Binary file storage. Keys are opaque paths such as {@code orders/2026/10/<uuid>.jpg}.
 */
public interface StorageService {

    void put(String key, InputStream content, long contentLength, String contentType);

    InputStream get(String key);

    void delete(String key);

    /** Time-limited URL the browser can load directly (e.g. an {@code <img src>}). */
    URL presignedGetUrl(String key);

    /** Cheap reachability check used by the health indicator. */
    boolean isAvailable();
}
