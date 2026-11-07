package com.fincalc.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.MissingFormatArgumentException;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void business_exception_exposes_code_and_formatted_message() {
        BusinessException ex = new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "periods", 0);
        assertThat(ex.errorCode().code()).isEqualTo("FCS-COM-0001");
        assertThat(ex.getMessage()).contains("periods").contains("0");
    }

    @Test
    void not_found_formats_its_own_template() {
        BusinessException ex = new BusinessException(CommonErrorCode.NOT_FOUND, "PRODUCT");
        assertThat(ex.errorCode().code()).isEqualTo("FCS-COM-0002");
        assertThat(ex.getMessage()).contains("PRODUCT");
    }

    @Test
    void state_conflict_exposes_its_code() {
        BusinessException ex = new BusinessException(CommonErrorCode.STATE_CONFLICT, "SETTLED", "DRAFT");
        assertThat(ex.errorCode().code()).isEqualTo("FCS-COM-0003");
        assertThat(ex.getMessage()).contains("SETTLED").contains("DRAFT");
    }

    @Test
    void args_are_kept_and_defensively_copied() {
        Object[] args = {"periods", 0};
        BusinessException ex = new BusinessException(CommonErrorCode.INVALID_ARGUMENT, args);

        args[1] = 99;

        assertThat(ex.args()).containsExactly("periods", 0);
    }

    @Test
    void formatted_message_contains_no_placeholder() {
        assertThat(new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "periods", 0).getMessage())
                .doesNotContain("%");
        assertThat(new BusinessException(CommonErrorCode.NOT_FOUND, "PRODUCT").getMessage())
                .doesNotContain("%");
        assertThat(new BusinessException(CommonErrorCode.STATE_CONFLICT, "SETTLED", "DRAFT").getMessage())
                .doesNotContain("%");
    }

    @Test
    void wrong_arity_fails_at_construction() {
        assertThatThrownBy(() -> new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "periods"))
                .isInstanceOf(MissingFormatArgumentException.class);
        assertThatThrownBy(() -> new BusinessException(CommonErrorCode.NOT_FOUND))
                .isInstanceOf(MissingFormatArgumentException.class);
    }

    @Test
    void cause_carrying_constructor_chains_the_original_failure() {
        BusinessException original = new BusinessException(CommonErrorCode.NOT_FOUND, "THIRTY_360");

        BusinessException translated = new BusinessException(
                CommonErrorCode.STATE_CONFLICT, original, "dayCount", "THIRTY_360");

        assertThat(translated.getCause()).isSameAs(original);
        assertThat(translated.errorCode()).isEqualTo(CommonErrorCode.STATE_CONFLICT);
        assertThat(translated.getMessage()).contains("dayCount").contains("THIRTY_360");
        assertThat(translated.args()).containsExactly("dayCount", "THIRTY_360");
    }

    @Test
    void system_exception_preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("boom");

        SystemException ex = new SystemException("failed to load extensions", cause);

        assertThat(ex).hasMessage("failed to load extensions");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
