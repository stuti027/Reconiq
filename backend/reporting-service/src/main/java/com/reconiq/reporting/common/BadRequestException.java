package com.reconiq.reporting.common;

/** Becomes HTTP 400 {"error": message}. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
