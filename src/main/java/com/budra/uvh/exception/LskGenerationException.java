package com.budra.uvh.exception;

/**
 * Exception indicating an error occurred specifically during the generation
 * or reservation of Logical Seed Key (LSK) values, often involving interaction
 * with the LskCounterRepository or underlying database issues during the atomic operation.
 * It can also wrap validation errors like invalid arguments passed for generation.
 */
public class LskGenerationException extends RuntimeException { // Extending RuntimeException as provided

    /**
     * Constructs a new LskGenerationException with the specified detail message.
     *
     * @param message the detail message.
     */
    public LskGenerationException(String message) {
        super(message);
    }

    /**
     * Constructs a new LskGenerationException with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause (e.g., SQLException, IllegalArgumentException).
     */
    public LskGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}