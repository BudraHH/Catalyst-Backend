package com.budra.uvh.exception;

public class PlaceholderFormatException extends Exception { // Checked exception

    public PlaceholderFormatException(String message) {
        super(message);
    }

    public PlaceholderFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}