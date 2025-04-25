package com.budra.uvh.exception;

public class LskGenerationException extends RuntimeException { // Or extend Exception for checked

    public LskGenerationException(String message) {
        super(message);
    }

    public LskGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}