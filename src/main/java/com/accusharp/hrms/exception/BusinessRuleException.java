package com.accusharp.hrms.exception;

/** A request that is well-formed but violates a domain rule. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
