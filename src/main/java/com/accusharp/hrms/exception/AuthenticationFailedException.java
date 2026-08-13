package com.accusharp.hrms.exception;

/** Login, refresh or credential verification failed. Maps to 401. */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
