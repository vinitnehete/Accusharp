package com.accusharp.hrms.security;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A report with money in it - payroll, statutory, bank, overtime amounts. Needs
 * {@code PAY_READ} as well as {@code REPORT_READ}: a supervisor reads their
 * team's attendance and leave reports, never what the team is paid.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@authz.can('REPORT_READ') and @authz.can('PAY_READ')")
public @interface PayReport {
}
