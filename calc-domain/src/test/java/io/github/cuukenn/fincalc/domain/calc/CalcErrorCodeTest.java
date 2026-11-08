package io.github.cuukenn.fincalc.domain.calc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The §3.4 code block lives in this enum, so a code exists in exactly one place.
 */
class CalcErrorCodeTest {

    @Test
    void carries_the_whole_schedule_validation_block() {
        assertThat(codes()).contains("FCS-CALC-3001", "FCS-CALC-3002", "FCS-CALC-3004");
    }

    @Test
    void declares_every_code_once() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    private static List<String> codes() {
        return Arrays.stream(CalcErrorCode.values())
                .map(CalcErrorCode::code)
                .collect(Collectors.toList());
    }
}
