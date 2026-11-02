package com.fincalc.domain.metric;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable bag of named financial metrics (NPV, IRR, XIRR, durations, ...). A code mapped to
 * {@code null} means "not computable for this schedule" — it is preserved as present-but-null
 * so a later engine can attach a {@code ValidationIssue} for it; absent codes were never computed.
 */
public final class FinancialMetrics {

    private final Map<String, BigDecimal> values;

    private FinancialMetrics(Map<String, BigDecimal> values) {
        this.values = values;
    }

    /** Snapshots {@code values} into an immutable, insertion-ordered map; {@code null} → empty. */
    public static FinancialMetrics of(Map<String, BigDecimal> values) {
        Map<String, BigDecimal> copy = values == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(values);
        return new FinancialMetrics(Collections.unmodifiableMap(copy));
    }

    /** A metrics bag with no entries. */
    public static FinancialMetrics empty() {
        return of(Collections.emptyMap());
    }

    /** Value for {@code code}, or {@code null} when absent or explicitly not computable. */
    public BigDecimal find(String code) {
        return values.get(code);
    }

    /** Codes carried by this bag, in insertion order. */
    public Set<String> codes() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
    }

    /** Unmodifiable view of all values. */
    public Map<String, BigDecimal> asMap() {
        return values;
    }
}
