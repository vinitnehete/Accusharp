package com.accusharp.hrms.enums;

/**
 * Access role carried on the employee record. Enforcement arrives with Spring
 * Security / JWT; today it is the data the authorization layer will read.
 */
public enum Role {
    ADMIN,
    HR,
    SUPERVISOR,
    EMPLOYEE
}
