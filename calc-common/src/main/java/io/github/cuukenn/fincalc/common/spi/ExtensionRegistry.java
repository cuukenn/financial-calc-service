package io.github.cuukenn.fincalc.common.spi;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.CommonErrorCode;
import io.github.cuukenn.fincalc.common.error.SystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Ordered registry of {@link OrderedExtension} implementations of one type.
 *
 * <p>Extensions are either discovered through {@link ServiceLoader} (see {@link #load(Class)}) or
 * registered explicitly (see {@link #register(OrderedExtension)}). At most one extension per
 * {@link OrderedExtension#code()} is kept: a later registration silently replaces the earlier one,
 * which lets an application override a discovered default.
 *
 * <p>Instances are mutable while being assembled and safe to read afterwards, as long as no thread
 * registers concurrently. {@link #all()} returns an immutable snapshot.
 *
 * @param <T> extension type
 */
public final class ExtensionRegistry<T extends OrderedExtension> {

    private final Map<String, T> byCode = new LinkedHashMap<>();

    private ExtensionRegistry() {
    }

    /**
     * Creates an empty registry for explicit registration.
     *
     * <p>Use this when a component owns a fixed set of built-in extensions and must not pick up
     * {@link ServiceLoader} providers from the classpath.
     *
     * @param <T> extension type
     * @return a mutable, empty registry
     */
    public static <T extends OrderedExtension> ExtensionRegistry<T> empty() {
        return new ExtensionRegistry<>();
    }

    /**
     * Creates a registry populated with every extension the {@link ServiceLoader} finds for
     * {@code type}.
     *
     * <p>Any {@link ServiceConfigurationError} raised by a broken provider declaration is reported as
     * a {@link SystemException}: it is an environment or packaging defect, not a business failure.
     *
     * <p>JPMS note: the extension type is a generic parameter, so the service dependency cannot be
     * declared statically with a {@code uses} directive in {@code module-info.java}. This method
     * therefore declares it dynamically via {@link Module#addUses(Class)} before loading. It is a
     * no-op on the classpath, where {@code uses} does not apply.
     *
     * @param type extension type; never {@code null}
     * @param <T>  extension type
     * @return a registry holding the discovered providers, ordered by
     *         {@link OrderedExtension#order()}
     * @throws SystemException if the service configuration cannot be read
     */
    public static <T extends OrderedExtension> ExtensionRegistry<T> load(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Module module = ExtensionRegistry.class.getModule();
        if (module.isNamed()) {
            module.addUses(type);
        }
        ExtensionRegistry<T> registry = new ExtensionRegistry<>();
        try {
            for (T extension : ServiceLoader.load(type)) {
                registry.register(extension);
            }
        } catch (ServiceConfigurationError ex) {
            throw new SystemException("failed to load extensions for " + type.getName(), ex);
        }
        return registry;
    }

    /**
     * Registers an extension, replacing any previously registered extension with the same
     * {@link OrderedExtension#code()}.
     *
     * @param extension extension to register; never {@code null}, must expose a non-null code
     * @return this registry, for chaining
     */
    public ExtensionRegistry<T> register(T extension) {
        Objects.requireNonNull(extension, "extension");
        String code = Objects.requireNonNull(extension.code(), "extension.code()");
        byCode.put(code, extension);
        return this;
    }

    /**
     * Returns the extension registered under {@code code}.
     *
     * @param code extension code
     * @return the registered extension, or an empty optional when absent
     */
    public Optional<T> find(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /**
     * Returns the extension registered under {@code code}.
     *
     * @param code extension code
     * @return the registered extension; never {@code null}
     * @throws BusinessException with {@link CommonErrorCode#NOT_FOUND} when no extension is
     *                           registered under {@code code}
     */
    public T require(String code) {
        return find(code)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND, code));
    }

    /**
     * Returns every registered extension sorted by ascending {@link OrderedExtension#order()}.
     *
     * @return an immutable snapshot; registration order breaks ties
     */
    public List<T> all() {
        List<T> extensions = new ArrayList<>(byCode.values());
        extensions.sort((left, right) -> Integer.compare(left.order(), right.order()));
        return Collections.unmodifiableList(extensions);
    }
}
