package com.reconiq.reporting.common;

/** Becomes HTTP 404 {"error": message}. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
