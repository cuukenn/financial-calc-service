package io.github.cuukenn.fincalc.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cuukenn.fincalc.common.money.MoneyScale;
import io.github.cuukenn.fincalc.common.money.MoneyUtils;
import io.github.cuukenn.fincalc.domain.model.CalculationOptions;
import io.github.cuukenn.fincalc.domain.model.CashFlowBasis;
import io.github.cuukenn.fincalc.domain.model.DepositSettlement;
import io.github.cuukenn.fincalc.domain.model.Installment;
import io.github.cuukenn.fincalc.domain.model.LeaseElement;
import io.github.cuukenn.fincalc.domain.metric.MetricCodes;
import io.github.cuukenn.fincalc.domain.model.RepaymentMethodId;
import io.github.cuukenn.fincalc.engine.CalculationFacade;
import io.github.cuukenn.fincalc.engine.CalculationRequest;
import io.github.cuukenn.fincalc.engine.CalculationResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The three deposit settlements of AGENTS.md 第 11 条, end to end (form-field-gaps ticket 03):
 * the calibers are pinned on {@code StandardCashFlowBuilderTest} and {@code LeaseElementTest}; this
 * class pins what a whole run prices with them.
 *
 * <p>The load-bearing economics: under 抵减本金 the deposit is consumed at collection against the
 * financed amount, so the run must price exactly the net principal — the same rent, the same
 * yield, as a deal contracted at {@code 合同本金 − 保证金} with no deposit at all. Anything else
 * would either double-count the deposit or hand the lessor a free yield.
 */
class DepositCaliberTest {

    private static final CalculationFacade FACADE = CalculationFacade.defaults();
    private static final CalculationOptions INCLUDE_DEPOSIT =
            new CalculationOptions(false, CashFlowBasis.INCLUDE_DEPOSIT);

    @Test
    void offset_principal_deal_prices_exactly_the_net_principal() {
        LeaseElement offset = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .deposit(new BigDecimal("100000"))
                .depositSettlement(DepositSettlement.OFFSET_PRINCIPAL)
                .build();
        LeaseElement net = Fixtures
                .leaseElement(new BigDecimal("900000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360");

        CalculationResult offsetRun = FACADE.calculate(
                new CalculationRequest(offset, null, INCLUDE_DEPOSIT));
        CalculationResult netRun = FACADE.calculate(
                new CalculationRequest(net, null, CalculationOptions.defaults()));

        List<Installment> offsetRows = offsetRun.schedule().installments();
        List<Installment> netRows = netRun.schedule().installments();
        assertThat(offsetRows).hasSameSizeAs(netRows);
        for (int i = 0; i < offsetRows.size(); i++) {
            assertThat(offsetRows.get(i).rentDue())
                    .as("period %d rents must match a deal contracted at the net principal", i + 1)
                    .isEqualByComparingTo(netRows.get(i).rentDue());
            assertThat(offsetRows.get(i).openingBalance())
                    .isEqualByComparingTo(netRows.get(i).openingBalance());
        }
        assertThat(offsetRun.schedule().hasErrors()).isFalse();
        // The event table nets to the same financed amount (gross principal out, deposit in at t0),
        // so the yield is the net deal's yield — not the gross deal's.
        assertThat(MoneyUtils.mid(offsetRun.schedule().header().metrics()
                .find(MetricCodes.IRR_PERIOD)))
                .isEqualByComparingTo(MoneyUtils.mid(netRun.schedule().header().metrics()
                        .find(MetricCodes.IRR_PERIOD)));
    }

    @Test
    void a_zero_deposit_run_is_bit_identical_to_the_v1_shape() {
        LeaseElement zero = Fixtures
                .leaseElement(new BigDecimal("2380000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .deposit(BigDecimal.ZERO)
                .build();
        LeaseElement none = Fixtures
                .leaseElement(new BigDecimal("2380000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360");

        CalculationResult zeroRun = FACADE.calculate(
                new CalculationRequest(zero, null, CalculationOptions.defaults()));
        CalculationResult noneRun = FACADE.calculate(
                new CalculationRequest(none, null, CalculationOptions.defaults()));

        assertThat(zeroRun.schedule().header().metrics().find(MetricCodes.IRR_PERIOD))
                .isEqualByComparingTo(noneRun.schedule().header().metrics()
                        .find(MetricCodes.IRR_PERIOD));
        assertThat(zeroRun.schedule().header().metrics().find(MetricCodes.TOTAL_RENT))
                .isEqualByComparingTo(noneRun.schedule().header().metrics()
                        .find(MetricCodes.TOTAL_RENT));
    }

    @Test
    void an_interest_bearing_refund_run_stays_green_and_keeps_rent_clean() {
        LeaseElement element = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360")
                .toBuilder()
                .deposit(new BigDecimal("50000"))
                .depositInterestRate(new BigDecimal("0.03"))
                .build();

        CalculationResult run = FACADE.calculate(
                new CalculationRequest(element, null, INCLUDE_DEPOSIT));

        assertThat(run.schedule().hasErrors()).isFalse();
        assertThat(run.schedule().header().metrics().find(MetricCodes.IRR_PERIOD)).isNotNull();
        // The deposit and its interest are event-table rows, never 租金: the rent total of the
        // deposit deal equals the rent total of the deposit-free deal with the same principal.
        LeaseElement plain = Fixtures
                .leaseElement(new BigDecimal("1000000"), new BigDecimal("0.035"), 12,
                        RepaymentMethodId.EQUAL_INSTALLMENT.name(), "THIRTY_360");
        CalculationResult plainRun = FACADE.calculate(
                new CalculationRequest(plain, null, CalculationOptions.defaults()));
        assertThat(MoneyUtils.scale(run.schedule().header().metrics()
                        .find(MetricCodes.TOTAL_RENT), MoneyScale.OUTPUT))
                .isEqualByComparingTo(MoneyUtils.scale(plainRun.schedule().header().metrics()
                        .find(MetricCodes.TOTAL_RENT), MoneyScale.OUTPUT));
    }
}
