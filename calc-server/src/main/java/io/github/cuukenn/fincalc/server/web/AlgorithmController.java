package io.github.cuukenn.fincalc.server.web;

import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.server.dto.AlgorithmDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The algorithm listing of Plan 2 Task 3: {@code GET /api/v1/algorithms} answers the supported
 * repayment method codes with their Chinese descriptions, so clients can drive
 * {@code POST /api/v1/calculations}'s {@code method} field without hard-coding it.
 *
 * <p>The code → description mapping is <b>hand-written</b> (Plan 2 Task 3 ruling): the domain
 * enum {@code RepaymentMethodId} stays annotation-free (R76 — core carries no documentation
 * annotations), and this map is the single server-side translation point. It mirrors the README
 * table「还款算法 code 对照表」; the static initializer fails fast if an enum constant is added
 * without a description here.
 */
@RestController
@Tag(name = "algorithms", description = "内置还款算法清单")
public class AlgorithmController {

    /** Chinese descriptions, one per {@link RepaymentMethodId} constant (mirrors the README table). */
    private static final Map<RepaymentMethodId, String> DESCRIPTIONS =
            new EnumMap<>(RepaymentMethodId.class);

    static {
        DESCRIPTIONS.put(RepaymentMethodId.EQUAL_INSTALLMENT,
                "等额租金（年金）：每期租金相等，末期尾差归入最后一期");
        DESCRIPTIONS.put(RepaymentMethodId.EQUAL_PRINCIPAL,
                "等额本金：每期归还固定本金，利息逐期递减");
        DESCRIPTIONS.put(RepaymentMethodId.INTEREST_FIRST,
                "先息后本：前 n−1 期只付利息，末期还本付息");
        DESCRIPTIONS.put(RepaymentMethodId.PRINCIPAL_FIRST,
                "先本后息：每期归还本金 P/n，利息于末期支付");
        DESCRIPTIONS.put(RepaymentMethodId.BULLET_LOAN,
                "到期一次还本付息：每期照常计提利息并递延（不复利、不进余额），期内每期本息实付为 0，"
                        + "到期日一次性结清本金与累计递延利息");
        DESCRIPTIONS.put(RepaymentMethodId.RULE_DRIVEN,
                "规则驱动：按编译后的规则集（请求携带 ruleRows）逐期解析租金/利率/本金计划");
        DESCRIPTIONS.put(RepaymentMethodId.EXPLICIT_RENT,
                "显式租金（设计 §8.1 04_现金流）：逐期租金给定（explicitRents），引擎反推本息拆分");
    }

    /**
     * Lists every repayment method code the engine routes on, in enum declaration order.
     * New {@code RepaymentMethodId} constants appear automatically; the description map above
     * is checked to stay complete at class-initialization time.
     */
    @GetMapping("/api/v1/algorithms")
    @Operation(summary = "列出全部内置还款算法（code + 中文说明）")
    public List<AlgorithmDto> algorithms() {
        List<AlgorithmDto> result = new ArrayList<>(RepaymentMethodId.values().length);
        for (RepaymentMethodId id : RepaymentMethodId.values()) {
            String description = DESCRIPTIONS.get(id);
            if (description == null) {
                throw new IllegalStateException(
                        "RepaymentMethodId." + id.name() + " has no description in "
                                + AlgorithmController.class.getSimpleName()
                                + " — add one (and update the README table)");
            }
            result.add(new AlgorithmDto(id.name(), description));
        }
        return result;
    }
}
