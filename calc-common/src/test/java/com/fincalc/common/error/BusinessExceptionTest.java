package com.fincalc.common.error;

import static org.assertj.core.api.Assertions.assertThat;

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
    void system_exception_preserves_message_and_cause() {
        IllegalStateException cause = new IllegalStateException("boom");

        SystemException ex = new SystemException("failed to load extensions", cause);

        assertThat(ex).hasMessage("failed to load extensions");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
