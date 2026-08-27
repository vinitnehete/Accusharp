package com.accusharp.hrms.enums;

/**
 * How an employee's salary structure came to change. Kept alongside the
 * before/after snapshot because the two directions mean different things: one
 * takes an employee off the company rule, the other puts them back on it.
 */
public enum SalaryStructureChangeType {

    /** Components set by hand. The structure is frozen and stops following gross salary. */
    OVERRIDE,

    /** The override dropped - components re-derived from the company {@code SalaryRule}. */
    REGENERATE
}
