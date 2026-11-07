/**
 * en-US 文案（R85）。类型标注 `typeof zhCN` 强制两语言键位完全一致（编译期对齐）。
 * R91: element names follow the design doc §4 unified language and industry usage.
 */
import type zhCN from './zh-CN'

const enUS: typeof zhCN = {
  app: {
    title: 'Lease Calculation Workbench',
    footer: 'financial-calc-service · visualization frontend (Plan 3/4)',
  },
  form: {
    cardTitle: 'Calculation Form',
    basic: {
      title: 'Lease Elements',
      principal: 'Lease principal',
      principalPh: 'e.g. 1000000',
      nominalRate: 'Nominal annual rate',
      // R92-6: percent-style input (6 or 6% → 0.06, 600bp → 0.06); converted to a
      // decimal string in the form layer before submit.
      nominalRatePh: 'e.g. 6% or 600bp',
      commenceDate: 'Commencement date',
      firstRentDate: 'First rent payment date',
      periods: 'Periods',
      currency: 'Currency',
      // R92-2: common-currency select (label "English name (code)", value = ISO-4217 code).
      currencies: {
        CNY: 'Chinese Yuan (CNY)',
        USD: 'US Dollar (USD)',
        EUR: 'Euro (EUR)',
        HKD: 'Hong Kong Dollar (HKD)',
        JPY: 'Japanese Yen (JPY)',
        GBP: 'British Pound (GBP)',
        SGD: 'Singapore Dollar (SGD)',
      },
      frequency: 'Payment frequency',
      timing: 'Payment timing',
      dayCountCode: 'Day count convention',
      dateRollCode: 'Business day convention',
      rateChangePolicy: 'Rate change policy',
      method: 'Repayment method',
    },
    rentStructure: {
      title: 'Rent Structure',
      ruleRows: {
        title: 'Rule rows (optional: segmented rates)',
        hint:
          'Available for every method: providing rule rows enables segmented rates '
          + '(required as the rule set for RULE_DRIVEN)',
        ruleIdPh: 'Rule ID',
        priorityPh: 'Priority',
        fromPeriodPh: 'From period',
        toPeriodPh: 'To period',
        // R92-6: rule-row annual rates use the same percent-style input (3 / 3% / 300bp → 0.03).
        annualRatePh: 'Annual rate (e.g. 3% or 300bp)',
        // R95 (review): inline format error + per-row submit-blocking message.
        rateFormat: 'Invalid rate format (e.g. 3 / 3% / 300bp)',
        rateFormatRow: 'Invalid rate format in rule row {n} (e.g. 3 / 3% / 300bp)',
        remove: 'Remove',
        add: '+ Add rule row',
        preset: "Segmented preset ({from}-{to} {'@'}3%, {from2}-{to2} {'@'}6%)",
      },
      explicitRents: {
        label: 'Per-period rents ({periods} periods in total, each must be > 0)',
        periodPh: 'Period {n}',
        incomplete: 'Explicit rents must fill all {periods} periods and each rent must be > 0',
      },
      grace: {
        title: 'Grace period (optional)',
        interestOnlyPeriods: 'Interest-only periods',
        fullGracePeriods: 'Full-grace periods',
      },
    },
    feesOther: {
      title: 'Fees & Others',
      fees: {
        title: 'Fees (optional)',
        typePh: 'Fee type',
        amountPh: 'Amount',
        remove: 'Remove',
        add: '+ Add fee',
      },
      // R91: §9.6 semantics — the amount is the purchase-option price under
      // SEPARATE and the balloon residual under BALLOON_IN_RENT.
      residualValue: 'Residual value / Purchase price',
      residualHandling: 'Residual handling',
      deposit: 'Deposit',
      vatRate: 'VAT rate',
      // R92-5: percent-style input (6 or 6% → 0.06), with China VAT bracket quick picks.
      vatRatePh: 'e.g. 6 or 6%',
      vatRateFormat: 'Invalid format: enter a VAT rate like 6 or 6%',
      vatRatePreset: 'VAT brackets: ',
    },
    submit: 'Calculate',
    submitting: 'Calculating…',
    submitFailed: 'Calculation failed ({code})',
    validation: {
      principal: 'Please enter the lease principal',
      nominalRate: 'Please enter the nominal annual rate',
      nominalRateFormat: 'Invalid format: enter a nominal annual rate like 6% or 600bp',
      commenceDate: 'Please select the commencement date',
      firstRentDate: 'Please select the first rent payment date',
      periods: 'Please enter the number of periods',
      frequency: 'Please select the payment frequency',
      timing: 'Please select the payment timing',
      dayCountCode: 'Please select the day count convention',
      dateRollCode: 'Please select the business day convention',
      method: 'Please select the repayment method',
    },
  },
  enums: {
    // R89: dropdown primary text is the localized name; internal codes never
    // appear in labels (day counts use the industry-standard slash notation,
    // with the code kept as secondary text inside the dropdown items only).
    frequency: {
      MONTHLY: 'Monthly',
      QUARTERLY: 'Quarterly',
      SEMI_ANNUAL: 'Semi-annual',
      ANNUAL: 'Annual',
      IRREGULAR: 'Irregular',
    },
    timing: {
      IN_ARREARS: 'End of period',
      IN_ADVANCE: 'Beginning of period',
    },
    dayCount: {
      THIRTY_360: '30/360',
      THIRTY_E_360: '30E/360',
      ACT_360: 'ACT/360',
      ACT_365F: 'ACT/365F',
      ACT_365_25: 'ACT/365.25',
      ACT_ACT: 'ACT/ACT',
    },
    dateRoll: {
      NONE: 'No adjustment',
      FOLLOWING: 'Following',
      MODIFIED_FOLLOWING: 'Modified following',
      PRECEDING: 'Preceding',
      MODIFIED_PRECEDING: 'Modified preceding',
      END_OF_MONTH: 'End of month',
    },
    residualHandling: {
      SEPARATE: 'Paid separately at end (purchase price)',
      BALLOON_IN_RENT: 'Amortized into rent (balloon)',
    },
    rateChangePolicy: {
      RESOLVE: 'Re-solve from change date',
      FIXED_RENT: 'Keep rent, absorb remainder',
      DRIFT: 'Roll difference into ending balance',
    },
    chargeMode: {
      UPFRONT: 'One-off upfront',
      DEDUCT_FROM_PRINCIPAL: 'Deducted from principal',
      AMORTIZED: 'Amortized into rent',
    },
    method: {
      EQUAL_INSTALLMENT: 'Equal installment (annuity)',
      EQUAL_PRINCIPAL: 'Equal principal',
      INTEREST_FIRST: 'Interest first, principal last',
      PRINCIPAL_FIRST: 'Principal first, interest last',
      RULE_DRIVEN: 'Rule driven',
      EXPLICIT_RENT: 'Explicit rents',
    },
  },
  result: {
    cardTitle: 'Calculation Result',
    runId: 'runId: {runId}',
    // R90a/R90b: after display formatting, the original backend string stays
    // available in the hover tooltip (no precision lost for reconciliation).
    raw: 'Raw: {value}',
    hasErrors: 'The result contains ERROR-level issues, see the issue list below',
    tabs: {
      metrics: 'Metrics',
      schedule: 'Amortization',
      cashflow: 'Cash Flow',
      eir: 'Effective Interest',
    },
    issues: {
      title: 'Issues',
      period: '(period {n})',
      levels: {
        INFO: 'Info',
        WARN: 'Warning',
        ERROR: 'Error',
      },
    },
    scheduleTab: {
      compositionTitle: 'Composition (principal vs interest)',
      balanceTitle: 'Balance curve',
      tableTitle: 'Amortization schedule',
    },
    cashflowTab: {
      tableTitle: 'Cash-flow table',
      curveTitle: 'Cumulative cash-flow recovery curve',
      empty: 'No cash-flow data',
      sequence: 'Seq',
      date: 'Date',
      type: 'Type',
      direction: 'Direction',
      amount: 'Amount',
      types: {
        PRINCIPAL: 'Principal',
        RENT: 'Rent',
        FEE: 'Fee',
        RESIDUAL: 'Residual',
        DEPOSIT: 'Deposit',
      },
      directions: {
        INFLOW: 'Inflow',
        OUTFLOW: 'Outflow',
      },
    },
    eir: {
      summary: 'Effective interest rate (EIR): {eir} · total interest adjustment: {diff}',
      empty: 'IRR did not converge or the table cannot be built; no effective-interest schedule',
    },
    table: {
      periodNo: 'Period',
      startDate: 'Start date',
      endDate: 'End date',
      days: 'Days',
      openingBalance: 'Opening balance',
      principalDue: 'Principal',
      interestAccrued: 'Interest (accrued)',
      interestPaid: 'Interest (paid)',
      fee: 'Fee',
      tax: 'Tax',
      rentDue: 'Rent',
      closingBalance: 'Closing balance',
      cumulativePrincipal: 'Cumulative principal',
      cumulativeInterest: 'Cumulative interest',
      appliedRate: 'Applied rate',
      memo: 'Memo',
      eirOpeningNetInvestment: 'Opening net investment',
      eirInterestIncome: 'Interest income',
      eirCashReceived: 'Cash received',
      eirPrincipalRecovery: 'Principal recovery',
      eirClosingNetInvestment: 'Closing net investment',
    },
  },
  charts: {
    principal: 'Principal',
    interest: 'Interest',
    periodLabel: 'Period {n}',
    periodAxis: 'Period',
    amount: 'Amount',
    closingBalance: 'Closing balance',
    cumulativeCashflow: 'Cumulative cash flow',
    dateAxis: 'Date',
  },
}

export default enUS
