package io.github.cuukenn.fincalc.domain.support.validate;

import io.github.cuukenn.fincalc.domain.calc.CalcErrorCode;
import io.github.cuukenn.fincalc.domain.model.IssueLevel;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.model.Schedule;
import io.github.cuukenn.fincalc.domain.model.ValidationIssue;
import io.github.cuukenn.fincalc.domain.port.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * AGENTS.md §2.3 阈值读数（form-field-gaps 15 / A2，仅 {@code ProductProfile#MORTGAGE} 装配）：
 *
 * <ul>
 *   <li><b>首付比例 ≥ 15%</b>（2024-09 通知，全国统一下限）——仅在要素携带 CAS 21 链
 *       （{@code assetValue} 与 {@code downPayment} 同时在）时可比；比较用精确乘法
 *       {@code downPayment < assetValue × 0.15}（P-10：未舍入原值判限），消息带两位
 *       百分数展示值（含本数：恰为 15.00% 不发）。</li>
 *   <li><b>期限 ≤ 480 月</b>（金规〔2026〕5号，40 年）——常规频率取 {@code periods}，
 *       IRREGULAR 取显式期间数。</li>
 * </ul>
 *
 * <p><b>级别一律 WARN</b>（form-field-gaps 15 决策点 ②）：引擎 ERROR 会点亮
 * {@code hasErrors()}——§9 的校验语义是「租金表不守恒，需人工复核」，合规读数混进去会
 * 污染 §3.4 四项校验的信号。前端闸门（提交拦截）保留，本校验器是随结果的合规披露层
 * （M-03：读数分区，不得像测算结果）。
 *
 * <p>不在 §3.4 的 code 顺序里：只由 {@code ValidatorChain.withMortgageThresholds()} 装配，
 * 缺省链（LEASE / 未声明产品的每一笔 run）不带它——issues 与 v1 逐字节一致。
 */
public final class MortgageThresholdValidator implements Validator {

    /** §2.3：首付比例全国统一下限（2024-09 通知）。 */
    public static final BigDecimal DOWN_PAYMENT_FLOOR = new BigDecimal("0.15");

    /** §2.3：房贷期限上限（金规〔2026〕5号，40 年）。 */
    public static final int MAX_TERM_MONTHS = 480;

    /** 首付比例的内部比较精度（P-07：不低于 10 位）。 */
    private static final int RATIO_SCALE = 10;

    @Override
    public String code() {
        return "MORTGAGE_THRESHOLDS";
    }

    @Override
    public int order() {
        // 跑在 §3.4 四项与日期单调之后：读数不该插在守恒信号前面。
        return 100;
    }

    @Override
    public List<ValidationIssue> validate(Schedule schedule, LeaseElement element) {
        List<ValidationIssue> issues = new ArrayList<>();
        BigDecimal assetValue = element.assetValue();
        BigDecimal downPayment = element.downPayment();
        if (assetValue != null && assetValue.signum() > 0
                && downPayment != null && downPayment.signum() >= 0) {
            // P-10：判限在未舍入原值上——用精确乘法 compare，不做除法舍入后再比。
            BigDecimal floorAmount = assetValue.multiply(DOWN_PAYMENT_FLOOR);
            if (downPayment.compareTo(floorAmount) < 0) {
                BigDecimal percent = downPayment.multiply(BigDecimal.valueOf(100))
                        .divide(assetValue, 2, RoundingMode.HALF_UP);
                issues.add(new ValidationIssue(IssueLevel.WARN,
                        CalcErrorCode.MORTGAGE_DOWN_PAYMENT_BELOW_FLOOR.code(),
                        CalcErrorCode.MORTGAGE_DOWN_PAYMENT_BELOW_FLOOR.code()
                                + ": 房贷首付比例低于下限 15%: " + percent.toPlainString()
                                + "%（首付 " + downPayment.toPlainString()
                                + " / 价值 " + assetValue.toPlainString() + "）",
                        null));
            }
        }
        int termMonths = element.frequency().isRegular()
                ? element.periods()
                : element.explicitPeriods().size();
        if (termMonths > MAX_TERM_MONTHS) {
            issues.add(new ValidationIssue(IssueLevel.WARN,
                    CalcErrorCode.MORTGAGE_TERM_OVER_LIMIT.code(),
                    CalcErrorCode.MORTGAGE_TERM_OVER_LIMIT.code()
                            + ": 房贷期限超过 480 个月（40 年）: " + termMonths + " 期",
                    null));
        }
        return issues;
    }
}
