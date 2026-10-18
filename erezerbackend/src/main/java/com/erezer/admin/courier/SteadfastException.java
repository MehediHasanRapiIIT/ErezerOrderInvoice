package com.erezer.admin.courier;

/** A failed Steadfast call, classified so callers know whether retrying can help. */
public class SteadfastException extends RuntimeException {

    public enum Kind {
        /** 400/422 or a business error in the body: fix the data, don't retry. */
        VALIDATION(false),
        /** 401: wrong or revoked keys. Never retried (10 failures in 5 min locks the account for an hour). */
        AUTH(false),
        /** 403: account inactive or KYC pending. */
        FORBIDDEN(false),
        NOT_FOUND(false),
        RATE_LIMITED(true),
        /** 5xx, timeout, network failure or non-JSON reply: the outcome of a booking is unknown. */
        UNAVAILABLE(true),
        UNEXPECTED(false);

        private final boolean retryable;

        Kind(boolean retryable) {
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;
    private final int httpStatus;

    public SteadfastException(Kind kind, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public SteadfastException(Kind kind, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
