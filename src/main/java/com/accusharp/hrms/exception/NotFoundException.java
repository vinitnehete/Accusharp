package com.accusharp.hrms.exception;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Object key) {
        return new NotFoundException(entity + " not found: " + key);
    }
}
