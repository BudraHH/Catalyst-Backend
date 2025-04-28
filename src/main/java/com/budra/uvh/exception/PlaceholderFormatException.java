package com.budra.uvh.exception;

/**
 * Exception indicating that an LSK (Logical Seed Key) placeholder string
 * encountered during processing does not conform to the expected format.
 */
public class PlaceholderFormatException extends RuntimeException { // Extending RuntimeException is common

    /**
     * Constructs a new PlaceholderFormatException with the specified detail message.
     *
     * @param message the detail message (e.g., describing the invalid placeholder).
     */
    public PlaceholderFormatException(String message) {
        super(message);
    }

    /**
     * Constructs a new PlaceholderFormatException with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause (which is saved for later retrieval by the getCause() method).
     *                (A null value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public PlaceholderFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}