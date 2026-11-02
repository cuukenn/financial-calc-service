package com.fincalc.domain.model;

/**
 * Whether rent is paid at the beginning or the end of each period.
 */
public enum PaymentTiming {

    /** Paid at the beginning of the period (期初). */
    IN_ADVANCE,

    /** Paid at the end of the period (期末). */
    IN_ARREARS
}
