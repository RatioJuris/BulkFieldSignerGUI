// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.time.Instant;

/** An update failure with a category suitable for logs and for choosing a recovery action. */
public final class UpdateException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Category {
        NETWORK("Network unavailable"), TIMEOUT("Timed out"), HTTP_STATUS("Server error"), RATE_LIMITED("GitHub rate limit reached"),
        INVALID_RESPONSE("Invalid server response"), METADATA("Invalid release information"), SIGNATURE("Signature check failed"),
        NO_PACKAGE("No package for this platform"), INCOMPLETE("Incomplete download"), CHECKSUM("Checksum mismatch"),
        INVALID_PACKAGE("Invalid update package"), INSTALL("Installation failed"), CANCELLED("Cancelled");
        private final String label;
        Category(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private final Category category;
    private final int httpStatus;
    private final transient Instant retryAfter;

    public UpdateException(Category category, String message) { this(category, message, 0, null, null); }
    public UpdateException(Category category, String message, Throwable cause) { this(category, message, 0, null, cause); }

    UpdateException(Category category, String message, int httpStatus, Instant retryAfter, Throwable cause) {
        super(message, cause);
        this.category = category;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Category category() { return category; }
    public int httpStatus() { return httpStatus; }
    /** When GitHub permits the next request, for rate limiting; may be null. */
    public Instant retryAfter() { return retryAfter; }
}
