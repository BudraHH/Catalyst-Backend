package com.budra.uvh.exception;

/**
 * Exception indicating an error occurred during the parsing of an XML document.
 * This could be due to syntax errors, I/O issues, or violations of expected structure/conventions.
 */
public class XmlParsingException extends RuntimeException {

    /**
     * Constructs a new XmlParsingException with the specified detail message.
     *
     * @param message the detail message.
     */
    public XmlParsingException(String message) {
        super(message);
    }

    /**
     * Constructs a new XmlParsingException with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause   the cause (which is saved for later retrieval by the getCause() method).
     *                (A null value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public XmlParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}