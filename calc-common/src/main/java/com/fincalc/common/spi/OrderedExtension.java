package com.fincalc.common.spi;

/**
 * Contract for pluggable extensions discovered through the registry.
 *
 * <p>Implementations are identified by a stable {@link #code()} and may declare a relative
 * {@link #order()}. Lower order values are exposed first by
 * {@link ExtensionRegistry#all()}; equal values keep their registration order.
 */
public interface OrderedExtension {

    /** Stable identifier of this extension, unique within its extension type. */
    String code();

    /** Relative position among extensions; defaults to {@code 100}. */
    default int order() {
        return 100;
    }
}
