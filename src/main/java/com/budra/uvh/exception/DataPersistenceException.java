package com.budra.uvh.exception;

/**
 * Exception indicating an error occurred during data persistence operations (e.g., saving to the database).
 */
public class DataPersistenceException extends RuntimeException {

    /**
     * Constructs a new DataPersistenceException with the specified detail message.
     *
     * @param message the detail message.
     */
    public DataPersistenceException(String message) {
        super(message);
    }

    /**
     * Constructs a new DataPersistenceException with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause (which is saved for later retrieval by the getCause() method).
     *                (A null value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public DataPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}