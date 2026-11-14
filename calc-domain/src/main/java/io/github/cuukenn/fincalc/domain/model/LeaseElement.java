package io.github.cuukenn.fincalc.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.cuukenn.fincalc.common.error.BusinessException;
import io.github.cuukenn.fincalc.common.error.CommonErrorCode;
import io.github.cuukenn.fincalc.common.money.MoneyTolerance;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;

/**
 * Immutable input of a single lease calculation: principal, dates, frequency, rate handling,
 * fees and rounding policy.
 *
 * <p>Instances are created only through {@link #builder()}; {@code build()} validates the
 * element and fails with a {@link BusinessException} carrying a stable {@code FCS-DOM-*} code.
 * Amounts and rates are {@link BigDecimal}; dates are {@link LocalDate}; collection fields are
 * unmodifiable.
 */
public final class LeaseElement {

    /** Hard cap on the effective period count: 1200 monthly periods, i.e. 100 years. */
    public static final int MAX_PERIODS = 1200;

    private final BigDecimal principal;
    private final BigDecimal assetValue;
    private final BigDecimal downPayment;
    private final BigDecimal initialDirectCosts;
    private final BigDecimal guaranteedResidual;
    private final BigDecimal unguaranteedResidual;
    private final BigDecimal nominalRate;
    private final LocalDate commenceDate;
    private final LocalDate firstRentDate;
    private final int periods;
    private final List<PeriodDate> explicitPeriods;
    private final PaymentFrequency frequency;
    private final PaymentTiming timing;
    private final String dayCountCode;
    private final String dateRollCode;
    private final RoundingPolicy rounding;
    private final BigDecimal residualValue;
    private final ResidualHandling residualHandling;
    private final BigDecimal deposit;
    private final DepositTiming depositTiming;
    private final DepositSettlement depositSettlement;
    private final BigDecimal depositInterestRate;
    private final boolean firstPeriodStub;
    private final List<FeeItem> fees;
    private final List<BigDecimal> explicitRents;
    private final BigDecimal vatRate;
    private final RepaymentMethodId method;
    private final GracePeriod gracePeriod;
    private final String currency;
    private final RateChangePolicy rateChangePolicy;

    private LeaseElement(Builder builder) {
        this.principal = builder.principal;
        this.assetValue = builder.assetValue;
        this.downPayment = builder.downPayment;
        this.initialDirectCosts = builder.initialDirectCosts;
        this.guaranteedResidual = builder.guaranteedResidual;
        this.unguaranteedResidual = builder.unguaranteedResidual;
        this.nominalRate = builder.nominalRate;
        this.commenceDate = builder.commenceDate;
        this.firstRentDate = builder.firstRentDate;
        this.periods = builder.periods;
        this.explicitPeriods = Collections.unmodifiableList(new ArrayList<>(builder.explicitPeriods));
        this.frequency = builder.frequency;
        this.timing = builder.timing;
        this.dayCountCode = builder.dayCountCode;
        this.dateRollCode = builder.dateRollCode;
        this.rounding = builder.rounding;
        this.residualValue = builder.residualValue;
        this.residualHandling = builder.residualHandling;
        this.deposit = builder.deposit;
        // The deposit calibers normalize in the constructor, not at the call sites: every engine
        // reader gets one non-null answer, and an element built without the fields keeps the v1
        // posture (upfront collection, refund at maturity, no interest) — AGENTS.md §3.1 第 11 条.
        this.depositTiming = builder.depositTiming == null
                ? DepositTiming.UPFRONT : builder.depositTiming;
        this.depositSettlement = builder.depositSettlement == null
                ? DepositSettlement.REFUND : builder.depositSettlement;
        this.depositInterestRate = builder.depositInterestRate;
        this.firstPeriodStub = builder.firstPeriodStub;
        this.fees = Collections.unmodifiableList(new ArrayList<>(builder.fees));
        this.explicitRents = Collections.unmodifiableList(new ArrayList<>(builder.explicitRents));
        this.vatRate = builder.vatRate;
        this.method = builder.method;
        this.gracePeriod = builder.gracePeriod;
        this.currency = builder.currency;
        this.rateChangePolicy = builder.rateChangePolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Derives a builder pre-filled with this element's values, for overrides and tests. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    private void validate() {
        if (principal == null || principal.compareTo(MoneyUtils.ZERO) <= 0) {
            throw new BusinessException(DomainErrorCode.PRINCIPAL_MUST_BE_POSITIVE, principal);
        }
        if (nominalRate == null
                || nominalRate.compareTo(MoneyUtils.ZERO) < 0
                || nominalRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(DomainErrorCode.NOMINAL_RATE_OUT_OF_RANGE, nominalRate);
        }
        if (periods < 1) {
            throw new BusinessException(DomainErrorCode.PERIODS_MUST_BE_POSITIVE, periods);
        }
        validatePeriodCount();
        if (commenceDate == null) {
            throw new BusinessException(DomainErrorCode.COMMENCE_DATE_REQUIRED);
        }
        if (firstRentDate == null || firstRentDate.isBefore(commenceDate)) {
            throw new BusinessException(DomainErrorCode.FIRST_RENT_DATE_BEFORE_COMMENCE, firstRentDate);
        }
        if (frequency == PaymentFrequency.IRREGULAR) {
            validateExplicitPeriods();
        }
        if (residualHandling == ResidualHandling.BALLOON_IN_RENT) {
            validateBalloonResidual();
        }
        validateStandardAmounts();
        validateExplicitRents();
        validateFees();
        validateDeposit();
        validateFirstPeriodStub();
        requireField(frequency, "frequency");
        requireField(timing, "timing");
        requireField(dayCountCode, "dayCountCode");
        requireField(dateRollCode, "dateRollCode");
        requireField(rounding, "rounding");
        requireField(residualHandling, "residualHandling");
        requireField(method, "method");
        requireField(gracePeriod, "gracePeriod");
        requireField(currency, "currency");
        requireField(rateChangePolicy, "rateChangePolicy");
    }

    /**
     * Caps the effective period count at {@link #MAX_PERIODS}: the {@code periods} of a regular
     * schedule, or the number of explicit windows when the frequency is
     * {@link PaymentFrequency#IRREGULAR}.
     */
    private void validatePeriodCount() {
        int effective = frequency == PaymentFrequency.IRREGULAR ? explicitPeriods.size() : periods;
        if (effective > MAX_PERIODS) {
            throw new BusinessException(DomainErrorCode.PERIODS_OUT_OF_RANGE, effective);
        }
    }

    private void validateExplicitPeriods() {
        if (explicitPeriods.isEmpty()) {
            throw new BusinessException(DomainErrorCode.IRREGULAR_PERIODS_INVALID, "显式期间为空");
        }
        for (PeriodDate period : explicitPeriods) {
            if (period == null
                    || period.startDate() == null
                    || period.endDate() == null
                    || !period.startDate().isBefore(period.endDate())) {
                throw new BusinessException(DomainErrorCode.IRREGULAR_PERIODS_INVALID, period);
            }
        }
    }

    private void validateBalloonResidual() {
        if (residualValue == null
                || residualValue.compareTo(MoneyUtils.ZERO) < 0
                || residualValue.compareTo(principal) >= 0) {
            throw new BusinessException(DomainErrorCode.BALLOON_RESIDUAL_OUT_OF_RANGE, residualValue);
        }
    }

    /**
     * {@link RepaymentMethodId#EXPLICIT_RENT} requires one strictly positive rent per period; any
     * other method must not carry explicit rents (fail loud instead of silently ignoring them).
     */
    /**
     * CAS 21 / IFRS 16 measurement chain: asset value + initial direct costs - down payment =
     * financed principal. Every amount is optional; when the asset value is absent the element
     * keeps the pre-R146 shape (principal given directly, no down payment, no IDC).
     */
    private void validateStandardAmounts() {
        requireNonNegative("downPayment", downPayment);
        requireNonNegative("initialDirectCosts", initialDirectCosts);
        requireNonNegative("guaranteedResidual", guaranteedResidual);
        requireNonNegative("unguaranteedResidual", unguaranteedResidual);
        if (assetValue == null) {
            // M-01: an amount that cannot move any cash flow or balance is a decoration, not an
            // input. Without an asset value the chain has no denominator, so these three would be
            // silently ignored (the down payment would even enter as a lone t0 inflow).
            requireAssetValueWhenPositive("downPayment", downPayment);
            requireAssetValueWhenPositive("initialDirectCosts", initialDirectCosts);
            requireAssetValueWhenPositive("unguaranteedResidual", unguaranteedResidual);
            return;
        }
        if (assetValue.signum() <= 0
                || (downPayment != null && downPayment.compareTo(assetValue) > 0)) {
            throw new BusinessException(DomainErrorCode.ASSET_VALUE_OUT_OF_RANGE, assetValue);
        }
        BigDecimal idc = initialDirectCosts == null ? MoneyUtils.ZERO : initialDirectCosts;
        BigDecimal dp = downPayment == null ? MoneyUtils.ZERO : downPayment;
        BigDecimal expected = assetValue.add(idc).subtract(dp);
        if (principal.subtract(expected).abs().compareTo(MoneyTolerance.MONEY_COMPARE) > 0) {
            throw new BusinessException(DomainErrorCode.PRINCIPAL_NOT_BALANCED, principal);
        }
    }

    private static void requireNonNegative(String name, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException(DomainErrorCode.NON_NEGATIVE_AMOUNT, name, value);
        }
    }

    private static void requireAssetValueWhenPositive(String name, BigDecimal value) {
        if (value != null && value.signum() > 0) {
            throw new BusinessException(DomainErrorCode.CHAIN_AMOUNT_REQUIRES_ASSET_VALUE,
                    name, value);
        }
    }

    private void validateExplicitRents() {
        if (method == RepaymentMethodId.EXPLICIT_RENT) {
            int expected = frequency == PaymentFrequency.IRREGULAR
                    ? explicitPeriods.size()
                    : periods;
            if (explicitRents.isEmpty() || explicitRents.size() != expected) {
                throw new BusinessException(DomainErrorCode.EXPLICIT_RENTS_INVALID, explicitRents.size());
            }
            for (BigDecimal rent : explicitRents) {
                if (rent == null || rent.compareTo(MoneyUtils.ZERO) <= 0) {
                    throw new BusinessException(DomainErrorCode.EXPLICIT_RENTS_MUST_BE_POSITIVE, rent);
                }
            }
        } else if (!explicitRents.isEmpty()) {
            // Same stable code FCS-DOM-1008 as the empty/length failure, but with its own
            // message so the cross-method rejection does not read self-contradictory.
            throw new BusinessException(DomainErrorCode.EXPLICIT_RENTS_CROSS_METHOD, method);
        }
    }

    private void validateFees() {
        for (FeeItem fee : fees) {
            if (fee == null || fee.amount() == null || fee.amount().compareTo(MoneyUtils.ZERO) < 0) {
                throw new BusinessException(DomainErrorCode.FEE_AMOUNT_INVALID,
                        fee == null ? null : fee.amount());
            }
        }
    }

    private void validateDeposit() {
        if (deposit != null && deposit.compareTo(MoneyUtils.ZERO) < 0) {
            throw new BusinessException(DomainErrorCode.DEPOSIT_MUST_BE_NON_NEGATIVE, deposit);
        }
        if (depositInterestRate == null) {
            return;
        }
        if (depositInterestRate.compareTo(MoneyUtils.ZERO) < 0
                || depositInterestRate.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException(DomainErrorCode.NOMINAL_RATE_OUT_OF_RANGE, depositInterestRate);
        }
        if (deposit == null || deposit.signum() <= 0) {
            throw new BusinessException(DomainErrorCode.DEPOSIT_INTEREST_RATE_REQUIRES_DEPOSIT,
                    depositInterestRate);
        }
        if (depositSettlement == DepositSettlement.OFFSET_PRINCIPAL) {
            // 抵减本金 consumes the deposit at collection: it never occupies a period, so there is
            // no time over which interest could accrue — carrying a rate is a caliber contradiction,
            // not a no-op (AGENTS.md 补充口径规则: 显式声明与口径矛盾的输入即报错).
            throw new BusinessException(DomainErrorCode.DEPOSIT_INTEREST_RATE_CONFLICTS_WITH_OFFSET,
                    depositInterestRate);
        }
    }

    /**
     * 129号① 的「整月」是一个日历概念：IRREGULAR 的显式期间没有月锚点；RULE_DRIVEN 的租金
     * 解算不消费 stub。两者都拒绝，不做静默忽略（§3.1 缺失/矛盾即报错）。
     */
    private void validateFirstPeriodStub() {
        if (!firstPeriodStub) {
            return;
        }
        if (frequency == PaymentFrequency.IRREGULAR) {
            throw new BusinessException(DomainErrorCode.FIRST_PERIOD_STUB_NOT_APPLICABLE,
                    "IRREGULAR（显式期间没有整月锚点）");
        }
        if (method == RepaymentMethodId.RULE_DRIVEN) {
            throw new BusinessException(DomainErrorCode.FIRST_PERIOD_STUB_NOT_APPLICABLE,
                    "RULE_DRIVEN（规则驱动租金解算不消费 stub）");
        }
    }

    private static void requireField(Object value, String name) {
        if (value == null) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, name, "必填");
        }
    }

    /** Financed principal; strictly positive. */
    public BigDecimal principal() {
        return principal;
    }

    public BigDecimal assetValue() {
        return assetValue;
    }

    public BigDecimal downPayment() {
        return downPayment;
    }

    public BigDecimal initialDirectCosts() {
        return initialDirectCosts;
    }

    public BigDecimal guaranteedResidual() {
        return guaranteedResidual;
    }

    public BigDecimal unguaranteedResidual() {
        return unguaranteedResidual;
    }

    /** Nominal annual rate as a fraction, e.g. {@code 0.06} for 6%; within [0, 1]. */
    public BigDecimal nominalRate() {
        return nominalRate;
    }

    /** Lease commencement date. */
    public LocalDate commenceDate() {
        return commenceDate;
    }

    /** Payment date of the first rent. */
    public LocalDate firstRentDate() {
        return firstRentDate;
    }

    /** Number of periods of the regular schedule. */
    public int periods() {
        return periods;
    }

    /** Explicit periods of an {@link PaymentFrequency#IRREGULAR} schedule; unmodifiable. */
    public List<PeriodDate> explicitPeriods() {
        return explicitPeriods;
    }

    /** Payment frequency. */
    public PaymentFrequency frequency() {
        return frequency;
    }

    /** Whether rent is paid in advance or in arrears. */
    public PaymentTiming timing() {
        return timing;
    }

    /** Day count convention code, e.g. {@code THIRTY_360}. */
    public String dayCountCode() {
        return dayCountCode;
    }

    /** Date roll convention code, e.g. {@code NONE}. */
    public String dateRollCode() {
        return dateRollCode;
    }

    /** Rounding policy of the calculation. */
    public RoundingPolicy rounding() {
        return rounding;
    }

    /** Residual value (purchase option price); may be {@code null}. */
    public BigDecimal residualValue() {
        return residualValue;
    }

    /** How the residual value enters the calculation. */
    public ResidualHandling residualHandling() {
        return residualHandling;
    }

    /**
     * Portion of the residual value that the schedule leaves outstanding at maturity: the residual
     * itself under {@link ResidualHandling#BALLOON_IN_RENT}, zero when it is settled separately or
     * when there is none.
     *
     * <p>The residual policy lives here so that generators and validators cannot disagree about
     * where a schedule is supposed to end and how much principal it must repay.
     */
    public BigDecimal outstandingResidual() {
        if (residualHandling != ResidualHandling.BALLOON_IN_RENT) {
            return MoneyUtils.ZERO;
        }
        return residualValue == null ? MoneyUtils.ZERO : residualValue;
    }

    /** Deposit amount; may be {@code null}. */
    public BigDecimal deposit() {
        return deposit;
    }

    /** When the deposit is collected; never {@code null} (defaults to {@link DepositTiming#UPFRONT}). */
    public DepositTiming depositTiming() {
        return depositTiming;
    }

    /**
     * How the deposit leaves the deal; never {@code null} (defaults to
     * {@link DepositSettlement#REFUND}, the v1 posture).
     */
    public DepositSettlement depositSettlement() {
        return depositSettlement;
    }

    /**
     * Annual interest rate the deposit earns while held; {@code null} means it does not earn
     * interest. Rejected at build time without a positive deposit and under
     * {@link DepositSettlement#OFFSET_PRINCIPAL}.
     */
    public BigDecimal depositInterestRate() {
        return depositInterestRate;
    }

    /**
     * Whether the first period splits into 整月 + 零头 (129号①: full months at the monthly rate,
     * remainder days at the daily rate) instead of one yearFraction segment (129号②, the default).
     * The two 129号 algorithms must not mix within one deal: with the stub on, the whole first
     * window prices ① and every later window stays on its own whole-period yearFraction.
     * Defaults to {@code false} — the v1 posture, bit-identical numbers.
     */
    public boolean firstPeriodStub() {
        return firstPeriodStub;
    }

    /**
     * The principal the schedule amortizes: the contract principal less a deposit whose settlement
     * is {@link DepositSettlement#OFFSET_PRINCIPAL} — the deposit is consumed at collection against
     * the financed amount, so rent and balances are measured on the net — and the contract principal
     * under every other settlement. Generators, the principal-conservation validator and the rule
     * pool all read this, so no amortization site can disagree with another about what the schedule
     * must repay; the event table keeps the deposit legs explicit and nets to the same number.
     */
    public BigDecimal measuredPrincipal() {
        if (depositSettlement == DepositSettlement.OFFSET_PRINCIPAL
                && deposit != null && deposit.signum() > 0) {
            return principal.subtract(deposit);
        }
        return principal;
    }

    /** Fees charged alongside the rent; unmodifiable. */
    public List<FeeItem> fees() {
        return fees;
    }

    /**
     * Explicit per-period rents, used only when the method is
     * {@link RepaymentMethodId#EXPLICIT_RENT}; unmodifiable, empty when unused.
     */
    public List<BigDecimal> explicitRents() {
        return explicitRents;
    }

    /** VAT rate; {@code null} means prices include tax. */
    public BigDecimal vatRate() {
        return vatRate;
    }

    /** Identifier of the amortization algorithm. */
    public RepaymentMethodId method() {
        return method;
    }

    /** Grace period at the start of the lease. */
    public GracePeriod gracePeriod() {
        return gracePeriod;
    }

    /** Currency code, e.g. {@code CNY}. */
    public String currency() {
        return currency;
    }

    /** How the schedule reacts to a mid-term rate change. */
    public RateChangePolicy rateChangePolicy() {
        return rateChangePolicy;
    }

    /** Builds {@link LeaseElement} instances; {@link #build()} validates before returning. */
    public static final class Builder {

        private BigDecimal principal;
        private BigDecimal assetValue;
        private BigDecimal downPayment;
        private BigDecimal initialDirectCosts;
        private BigDecimal guaranteedResidual;
        private BigDecimal unguaranteedResidual;
        private BigDecimal nominalRate;
        private LocalDate commenceDate;
        private LocalDate firstRentDate;
        private int periods;
        private List<PeriodDate> explicitPeriods = Collections.emptyList();
        private PaymentFrequency frequency;
        private PaymentTiming timing;
        private String dayCountCode;
        private String dateRollCode;
        private RoundingPolicy rounding;
        private BigDecimal residualValue;
        private ResidualHandling residualHandling;
        private BigDecimal deposit;
        private DepositTiming depositTiming;
        private DepositSettlement depositSettlement;
        private BigDecimal depositInterestRate;
        private boolean firstPeriodStub;
        private List<FeeItem> fees = Collections.emptyList();
        private List<BigDecimal> explicitRents = Collections.emptyList();
        private BigDecimal vatRate;
        private RepaymentMethodId method;
        private GracePeriod gracePeriod;
        private String currency;
        private RateChangePolicy rateChangePolicy;

        private Builder() {
        }

        private Builder(LeaseElement source) {
            this.principal = source.principal;
            this.assetValue = source.assetValue;
            this.downPayment = source.downPayment;
            this.initialDirectCosts = source.initialDirectCosts;
            this.guaranteedResidual = source.guaranteedResidual;
            this.unguaranteedResidual = source.unguaranteedResidual;
            this.nominalRate = source.nominalRate;
            this.commenceDate = source.commenceDate;
            this.firstRentDate = source.firstRentDate;
            this.periods = source.periods;
            this.explicitPeriods = source.explicitPeriods;
            this.frequency = source.frequency;
            this.timing = source.timing;
            this.dayCountCode = source.dayCountCode;
            this.dateRollCode = source.dateRollCode;
            this.rounding = source.rounding;
            this.residualValue = source.residualValue;
            this.residualHandling = source.residualHandling;
            this.deposit = source.deposit;
            this.depositTiming = source.depositTiming;
            this.depositSettlement = source.depositSettlement;
            this.depositInterestRate = source.depositInterestRate;
            this.firstPeriodStub = source.firstPeriodStub;
            this.fees = source.fees;
            this.explicitRents = source.explicitRents;
            this.vatRate = source.vatRate;
            this.method = source.method;
            this.gracePeriod = source.gracePeriod;
            this.currency = source.currency;
            this.rateChangePolicy = source.rateChangePolicy;
        }

        public Builder principal(BigDecimal principal) {
            this.principal = principal;
            return this;
        }

        public Builder assetValue(BigDecimal assetValue) {
            this.assetValue = assetValue;
            return this;
        }

        public Builder downPayment(BigDecimal downPayment) {
            this.downPayment = downPayment;
            return this;
        }

        public Builder initialDirectCosts(BigDecimal initialDirectCosts) {
            this.initialDirectCosts = initialDirectCosts;
            return this;
        }

        public Builder guaranteedResidual(BigDecimal guaranteedResidual) {
            this.guaranteedResidual = guaranteedResidual;
            return this;
        }

        public Builder unguaranteedResidual(BigDecimal unguaranteedResidual) {
            this.unguaranteedResidual = unguaranteedResidual;
            return this;
        }

        public Builder nominalRate(BigDecimal nominalRate) {
            this.nominalRate = nominalRate;
            return this;
        }

        public Builder commenceDate(LocalDate commenceDate) {
            this.commenceDate = commenceDate;
            return this;
        }

        public Builder firstRentDate(LocalDate firstRentDate) {
            this.firstRentDate = firstRentDate;
            return this;
        }

        public Builder periods(int periods) {
            this.periods = periods;
            return this;
        }

        /** A {@code null} list is treated as empty. */
        public Builder explicitPeriods(List<PeriodDate> explicitPeriods) {
            this.explicitPeriods = explicitPeriods == null ? Collections.emptyList() : explicitPeriods;
            return this;
        }

        public Builder frequency(PaymentFrequency frequency) {
            this.frequency = frequency;
            return this;
        }

        public Builder timing(PaymentTiming timing) {
            this.timing = timing;
            return this;
        }

        public Builder dayCountCode(String dayCountCode) {
            this.dayCountCode = dayCountCode;
            return this;
        }

        public Builder dateRollCode(String dateRollCode) {
            this.dateRollCode = dateRollCode;
            return this;
        }

        public Builder rounding(RoundingPolicy rounding) {
            this.rounding = rounding;
            return this;
        }

        public Builder residualValue(BigDecimal residualValue) {
            this.residualValue = residualValue;
            return this;
        }

        public Builder residualHandling(ResidualHandling residualHandling) {
            this.residualHandling = residualHandling;
            return this;
        }

        public Builder deposit(BigDecimal deposit) {
            this.deposit = deposit;
            return this;
        }

        /** When the deposit is collected; {@code null} means the v1 posture (upfront). */
        public Builder depositTiming(DepositTiming depositTiming) {
            this.depositTiming = depositTiming;
            return this;
        }

        /** How the deposit leaves the deal; {@code null} means the v1 posture (refund). */
        public Builder depositSettlement(DepositSettlement depositSettlement) {
            this.depositSettlement = depositSettlement;
            return this;
        }

        /**
         * Annual interest rate the deposit earns while held, as a fraction within [0, 1];
         * {@code null} means no interest. Requires a positive deposit and a settlement other than
         * {@link DepositSettlement#OFFSET_PRINCIPAL}.
         */
        public Builder depositInterestRate(BigDecimal depositInterestRate) {
            this.depositInterestRate = depositInterestRate;
            return this;
        }

        /**
         * Whether the first window prices 129号① (整月 + 零头) instead of one yearFraction
         * segment; defaults to {@code false}. Rejected for {@code IRREGULAR} and
         * {@code RULE_DRIVEN} at build time.
         */
        public Builder firstPeriodStub(boolean firstPeriodStub) {
            this.firstPeriodStub = firstPeriodStub;
            return this;
        }

        /** A {@code null} list is treated as empty. */
        public Builder fees(List<FeeItem> fees) {
            this.fees = fees == null ? Collections.emptyList() : fees;
            return this;
        }

        /**
         * Explicit per-period rents for {@link RepaymentMethodId#EXPLICIT_RENT}; a {@code null}
         * list is treated as empty. Rejected at build time when the method is not
         * {@code EXPLICIT_RENT}.
         */
        public Builder explicitRents(List<BigDecimal> explicitRents) {
            this.explicitRents = explicitRents == null ? Collections.emptyList() : explicitRents;
            return this;
        }

        public Builder vatRate(BigDecimal vatRate) {
            this.vatRate = vatRate;
            return this;
        }

        public Builder method(RepaymentMethodId method) {
            this.method = method;
            return this;
        }

        public Builder gracePeriod(GracePeriod gracePeriod) {
            this.gracePeriod = gracePeriod;
            return this;
        }

        public Builder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public Builder rateChangePolicy(RateChangePolicy rateChangePolicy) {
            this.rateChangePolicy = rateChangePolicy;
            return this;
        }

        public LeaseElement build() {
            LeaseElement element = new LeaseElement(this);
            element.validate();
            return element;
        }
    }
}
