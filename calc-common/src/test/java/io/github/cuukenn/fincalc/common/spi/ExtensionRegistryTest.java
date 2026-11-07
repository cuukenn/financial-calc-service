package io.github.cuukenn.fincalc.common.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.CommonErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtensionRegistryTest {

    @Test
    void explicit_registration_overrides_and_sorts_by_order() {
        ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);
        reg.register(new FakeExt("b", 20));
        reg.register(new FakeExt("a", 10));
        reg.register(new FakeExt("a", 30));

        // a keeps the override's order (30), b stays at 20 — ascending sort exposes b first
        assertThat(reg.all()).extracting(FakeExt::code).containsExactly("b", "a");
        assertThat(reg.require("a").order()).isEqualTo(30);
        assertThatThrownBy(() -> reg.require("missing")).isInstanceOf(BusinessException.class);
    }

    @Test
    void require_reports_the_missing_code() {
        ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);

        assertThatThrownBy(() -> reg.require("calc.unknown"))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                    assertThat(ex.getMessage()).contains("calc.unknown");
                });
    }

    @Test
    void find_returns_empty_when_absent() {
        ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);

        assertThat(reg.find("missing")).isEmpty();

        reg.register(new FakeExt("a", 10));

        assertThat(reg.find("a")).map(FakeExt::order).hasValue(10);
    }

    @Test
    void order_defaults_to_one_hundred() {
        assertThat(new DefaultOrderExt().order()).isEqualTo(100);
    }

    @Test
    void all_is_unmodifiable_and_stable_for_equal_orders() {
        ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);
        reg.register(new FakeExt("z", 10));
        reg.register(new FakeExt("a", 10));

        List<FakeExt> all = reg.all();

        assertThat(all).extracting(FakeExt::code).containsExactly("z", "a");
        assertThatThrownBy(() -> all.add(new FakeExt("x", 0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registering_null_is_rejected() {
        ExtensionRegistry<FakeExt> reg = ExtensionRegistry.load(FakeExt.class);

        assertThatThrownBy(() -> reg.register(null)).isInstanceOf(NullPointerException.class);
    }

    public static final class FakeExt implements OrderedExtension {

        private final String code;
        private final int order;

        private FakeExt(String code, int order) {
            this.code = code;
            this.order = order;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public int order() {
            return order;
        }
    }

    @Test
    void empty_registry_starts_with_no_extensions_and_accepts_registration() {
        ExtensionRegistry<FakeExt> registry = ExtensionRegistry.empty();

        assertThat(registry.all()).isEmpty();

        registry.register(new FakeExt("a", 10));

        assertThat(registry.all()).extracting(FakeExt::code).containsExactly("a");
    }

    public static final class DefaultOrderExt implements OrderedExtension {

        @Override
        public String code() {
            return "default";
        }
    }
}
