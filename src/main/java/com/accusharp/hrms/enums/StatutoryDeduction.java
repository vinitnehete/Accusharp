package com.accusharp.hrms.enums;

/**
 * The deductions payroll computes by itself, which a work policy may leave out
 * for a population - a director who is not a PF member, say. TDS, advances,
 * loans and canteen are not here: they are entered per payroll run, so there is
 * nothing to switch off.
 */
public enum StatutoryDeduction {
    PF("PF"),
    ESIC("ESIC"),
    PROFESSIONAL_TAX("professional tax"),
    MLWF("MLWF");

    private final String label;

    StatutoryDeduction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
