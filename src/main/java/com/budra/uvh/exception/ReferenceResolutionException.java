package com.budra.uvh.exception;

// Exception for errors during the FK reference resolution phase
public class ReferenceResolutionException extends LskGenerationException { // Or extend Exception

    public ReferenceResolutionException(String message) {
        super(message);
    }

    public ReferenceResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}