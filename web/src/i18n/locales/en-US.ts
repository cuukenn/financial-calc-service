/**
 * en-US 文案（R85）。类型标注 `typeof zhCN` 强制两语言键位完全一致（编译期对齐）。
 * R91: element names follow the design doc §4 unified language and industry usage.
 */
import type zhCN from './zh-CN'

const enUS: typeof zhCN = {
  app: {
    title: 'Lease Calculation Workbench',
    footer: 'financial-calc-service · visualization frontend (Plan 3/4)',
    // R100: the two scenarios share the one engine and the one POST /api/v1/calculations contract.
    scenario: {
      lease: 'Finance Lease',
      mortgage: 'Mortgage Calculator',
    },
  },
  form: {
    cardTitle: 'Calculation Form',
    basic: {
      title: 'Lease Elements',
      principal: 'Lease principal',
      assetValue: 'Asset value (fair value)',
      assetValuePh: 'Optional; the financed principal is derived from it',
      financedPrincipal: 'Financed principal: {value}',
      downPayment: 'Down payment',
      downPaymentPh: 'e.g. 400000',
      principalPh: 'e.g. 1000000',
      nominalRate: 'Nominal annual rate',
      // R92-6: percent-style input (6 or 6% → 0.06, 600bp → 0.06); converted to a
      // decimal string in the form layer before submit.
      nominalRatePh: 'e.g. 6% or 600bp',
      commenceDate: 'Commencement date',
      firstRentDate: 'First rent payment date',
      periods: 'Periods',
      frequency: 'Payment frequency',
      timing: 'Payment timing',
      dayCountCode: 'Day count convention',
      dateRollCode: 'Business day convention',
      rateChangePolicy: 'Rate change policy',
      method: 'Repayment method',
    },
    rentStructure: {
      title: 'Rent Structure',
      firstPeriodStub: 'Split the first period into full months + odd days (129 art. ①)',
      firstPeriodStubHint: 'Off by default: one year-fraction segment for the first window (129 art. ②)',
      firstPeriodStubNote: 'The first window prices as full months at the monthly rate plus remainder days at the daily rate (annual ÷ 360); the two 129 methods never mix within one deal. The first row of the schedule carries the caliber note.',
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
        includeIrr: 'Include in IRR',
        title: 'Fees (optional)',
        typePh: 'Fee type',
        amountPh: 'Amount',
        remove: 'Remove',
        add: '+ Add fee',
      },
      // §2.1 / M-01: this field is the purchase option price (nominal) — a real terminal
      // cash flow. The residual value (unguaranteed) is not modelled: its only consumer is the
      // lease implicit rate (CAS 21), which is not implemented yet.
      residualValue: 'Purchase option price (nominal)',
      residualValueHint: 'Paid at maturity to acquire the asset; 0 when there is no purchase option',
      residualHandling: 'Purchase option handling',
      deposit: 'Deposit',
      depositTiming: 'Deposit collection timing',
      depositSettlement: 'Refund / offset rule',
      depositSettlementHint: 'Offset principal: rent measured on contract principal minus deposit; offset final rent: deposit settles the last rent at maturity',
      depositInterestRate: 'Deposit annual rate (interest-bearing)',
      depositInterestRatePh: 'e.g. 3 or 3%',
      depositInterestRateHint: 'Interest accrues over the days actually held (ACT/360, head-inclusive/tail-exclusive, simple), paid as its own row; not allowed under offset-principal',
      amountPh: 'e.g. 0',
      initialDirectCosts: 'Initial direct costs (IDC)',
      guaranteedResidual: 'Guaranteed residual',
      guaranteedResidualHint: 'Cash actually collected at maturity (CAS 21 lease receipts)',
      unguaranteedResidual: 'Unguaranteed residual',
      unguaranteedResidualHint: 'Feeds the lease implicit rate only; it never becomes cash',
    },
    submit: 'Calculate',
    submitting: 'Calculating…',
    chainRequiresAssetValue: 'Down payment / IDC / unguaranteed residual require an asset value',
    submitFailed: 'Calculation failed ({code})',
    // Task 6 §2: explicit reset (both forms have their own reset button, shared copy).
    reset: 'Reset form',
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
  mortgage: {
    // Task 5 / R101: the mortgage scenario goes through the same
    // POST /api/v1/calculations contract; element names follow AGENTS.md §2.2
    // (loan amount / down payment ratio / LTV / monthly housing-expense-to-income
    // ratio / total monthly debt-to-income ratio) — no synonyms.
    cardTitle: 'Mortgage Calculation Form',
    basic: {
      title: 'Loan Elements',
      principal: 'Loan amount',
      principalPh: 'e.g. 2380000',
      lpr: 'Current LPR (%)',
      // P-04: this project has no LPR data source, the user enters the LPR as a percent (3.5 = 3.5%).
      lprPh: 'e.g. 3.5',
      spread: 'Spread (bp)',
      // P-04: LPR quoting step is 0.05pp = 5bp, only multiples of 5bp are accepted.
      spreadPh: 'e.g. 35 (must be a multiple of 5bp)',
      composedRate: 'Annual rate (LPR + spread)',
      composedRateHint: 'Decimal annual rate actually sent to the engine (P-02)',
      composedRateInvalid: 'Not parseable, check the LPR and the spread',
      commenceDate: 'Disbursement date',
      firstRepaymentDate: 'First repayment date',
      periods: 'Term (months)',
      method: 'Repayment method',
    },
    collateral: {
      // R103: LTV / down payment ratio are front-end derived metrics, never engine metrics.
      title: 'Collateral & Leverage (front-end only, not sent)',
      transactionPrice: 'Transaction price',
      appraisalValue: 'Appraised value',
      collateralValue: 'Collateral value (lower of price and appraisal)',
      ltv: 'Loan-to-value (LTV)',
      downPaymentRatio: 'Down payment ratio',
    },
    income: {
      // R103: DTI is a front-end derived metric too.
      title: 'Income & Debts (front-end only, not sent)',
      monthlyIncome: 'Monthly disposable income',
      monthlyIncomePh: 'e.g. 16000',
      otherMonthlyDebt: 'Other monthly debt payments',
      otherMonthlyDebtPh: 'e.g. 1600',
      monthlyPayment: 'Monthly payment',
      dti: 'Monthly housing expense / income ratio',
      allDebtRatio: 'Total monthly debt / income ratio',
      // AGENTS.md §9.5 posture: an uncomputable metric must say so, never a silent 0.
      unavailable: 'No income provided, DTI cannot be determined',
      thresholds:
        'Thresholds and colours: housing-expense ratio ≤ 50% compliant, > 50% approaching the limit, > 60% over the limit; all-debt ratio ≤ 60% compliant, > 60% over the limit; equal to a limit is not a breach (judged on the unrounded value).',
      paymentNote: 'Monthly payment basis: first installment for equal installments; largest installment for equal principal and bullet loans (worst month).',
      // Task 6 §5 (copy ownership handed over from Task 5): a bullet loan repays one lump sum.
      // The row heading gets its own name under §2.2 instead of posing as a monthly payment;
      // the arithmetic and the DTI basis stay exactly as they were.
      lumpSumPayment: 'Lump-sum repayment at maturity',
      lumpSumBasis:
        'Bullet basis: this figure is the principal plus all deferred interest repaid in a single payment at maturity (the largest installment in the schedule) — it is not a monthly payment; the 月供 basis of §2.2 applies to the monthly methods only.',
      lumpSumProxyNote:
        'Comparing this lump-sum maturity payment directly with monthly disposable income is a deliberately conservative proxy: the ratio is far larger than the burden actually spread across the months, so it flags the risk of this method.',
    },
    status: {
      // Task 6 §3: one text marker per status, so meaning is not carried by colour alone.
      ok: 'Compliant',
      warn: 'Approaching the limit',
      breach: 'Over the limit',
      // A floor breach points the other way: below the floor, not over a ceiling.
      belowFloor: 'Below the floor',
      neutral: 'Not evaluable',
    },
    // R150: repricing is the element a mortgage contract changes most often (AGENTS.md §2.2
    // 重定价日 / 重定价周期). It lands as the engine's existing segmented-rate rule rows, no new contract key.
    repricing: {
      cycle: 'Repricing cycle',
      hint: 'No repricing = the fixed-rate path. Choose 3 / 6 / 12 months (央行公告〔2024〕第 11 号 offers exactly these three; the value gate lives in the UI, the engine only sees segmented rates).',
      cycles: {
        none: 'No repricing (fixed rate)',
        months: '{months} months',
      },
      anchorDate: 'Repricing date',
      anchorPh: 'Empty = the disbursement anniversary',
      futureLprs: 'LPR of the later segments (%)',
      futureLprsPh: 'e.g. 3.9, 3.1 (in segment order, comma separated)',
      futureLprsHint:
        'Give the LPR in force at each repricing, in segment order; the spread stays (P-04: the spread is a contract term, repricing re-fixes the LPR only). A missing segment is never filled in with the current LPR silently.',
      reuse: 'Reuse the current LPR for missing segments (an assumption, not a contract fact)',
      readoutTitle: 'Segment rates (checkable before submit)',
      currentRate: 'Rate in force now',
      currentRateFrom: 'from',
      nextDate: 'Next repricing date',
      nextDateNone: 'No further repricing within the term',
      segmentRange: 'Periods {from}–{to} (from {date})',
      rateMissing: 'LPR missing for this segment',
      assumedNotice:
        'The LPR of {count} of these segments was filled in by the "reuse the current LPR" assumption: it is not a rate the contract stated, and the result follows that assumption (AGENTS.md: apply the documented convention and label it).',
    },
    gates: {
      // §71: these [法] thresholds are validated in the UI, the copy must not claim the engine checks them (R107/R109).
      note: 'Client-side gates: down payment ≥ 15%, term ≤ 480 months and the 50%/60% DTI limits are blocked by this form; with product=MORTGAGE the engine also discloses the down-payment and term thresholds as WARN findings (DTI stays front-end: the engine has no income input, M-01).',
      bulletOnly: 'Bullet (principal and interest at maturity) is selectable only when the term is ≤ {max} months (金规〔2026〕5号: only terms ≤ 1 year may repay at maturity).',
      bulletReset: 'Term exceeds {max} months: bullet repayment is not selectable and the choice was reset to equal installments (front-end rule, not engine-validated).',
      downPaymentFloor: 'Down payment ratio is below the {min} floor (currently {actual}): submit is blocked (front-end floor; the engine also discloses it as a WARN finding — issue 15/A2, disclosure only).',
      // AGENTS.md §2.3 "the threshold is a hard constraint": an unevaluable floor must not pass silently.
      downPaymentFloorNotEvaluable:
        'The collateral value is not greater than 0 (the lower of transaction price and appraised value), so LTV and the down payment ratio cannot be computed: the {min} down payment floor cannot be evaluated and submit is blocked (front-end floor; the engine discloses a WARN only when the element carries the chain fields).',
      rateNotComposable: 'The annual rate cannot be composed from the LPR and the spread, check the inputs.',
      // R150: the three repricing gates, all of them in the UI (the engine does not know the word
      // "repricing cycle" — it only sees segmented-rate rule rows).
      repricingCycle:
        'The repricing cycle must be 3 / 6 / 12 months (央行公告〔2024〕第 11 号): submit is blocked (UI-validated value, not engine-validated).',
      repricingCoverage:
        'The repricing segments do not cover periods 1 to {periods} contiguously: submit is blocked (the engine RuleSetValidator rejects the same gap; failing here avoids a network round trip).',
      repricingMissingLpr:
        '{count} segment(s) have no LPR: the first-segment LPR must not be reused silently (AGENTS.md §3.1 — a missing input is an error). Supply the later LPRs, or check "reuse the current LPR" and treat it as an assumption.',
      // Ticket 08: the two gates of the partial-prepayment recalculation.
      prepayNotPartial: 'The partial prepayment must be positive and below the remaining principal (≥ the remaining principal is a full settlement — read the settlement amount instead).',
      prepayNoPeriods: 'Not enough remaining periods: the recalculation needs at least one.',
    },
    validation: {
      principal: 'Please enter the loan amount',
      // R101: principal is a positive decimal string — 0 and negatives stop here, no needless engine round trip.
      principalPositive: 'The loan amount must be greater than 0 (a zero or negative principal is not a valid calculation element)',
      money: 'Invalid amount format: enter a non-negative decimal number (e.g. 2380000)',
      lpr: 'Please enter the current LPR',
      lprFormat: 'Invalid LPR format: enter a percent-scale decimal number (e.g. 3.5)',
      spread: 'Please enter the spread',
      spreadStep: 'The spread must be an integer multiple of 5bp (P-04: LPR quoting step is 0.05 percentage points), it is never rounded silently',
      commenceDate: 'Please select the disbursement date',
      firstRepaymentDate: 'Please select the first repayment date',
      firstRepaymentBeforeCommence: 'The first repayment date must not precede the disbursement date (the engine rejects it)',
      // R150: field-level checks for the three repricing elements.
      repricingCycle: 'The repricing cycle must be 0 (no repricing) or 3 / 6 / 12 months',
      repricingAnchorBeforeCommence: 'The repricing date must not precede the disbursement date (a repricing event before disbursement does not belong to this loan)',
      futureLprs: 'Invalid later-LPR format: enter comma separated non-negative percentages (e.g. 3.9, 3.1)',
      periods: 'Please enter the loan term in months',
      periodsRange: 'The loan term must be within 1–{max} months (≤ 40 years)',
      transactionPrice: 'Please enter the transaction price',
      appraisalValue: 'Please enter the appraised value',
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
    depositTiming: {
      UPFRONT: 'Collected at commencement',
      WITH_FIRST_RENT: 'With the first rent',
    },
    depositSettlement: {
      REFUND: 'Refunded at maturity',
      OFFSET_PRINCIPAL: 'Offset against principal',
      OFFSET_FINAL_RENT: 'Offset against final rent',
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
    feeOwner: {
      LESSEE: 'Borne by lessee',
      LESSOR: 'Borne by lessor',
    },
    method: {
      EQUAL_INSTALLMENT: 'Equal installment (annuity)',
      EQUAL_PRINCIPAL: 'Equal principal',
      INTEREST_FIRST: 'Interest first, principal last',
      PRINCIPAL_FIRST: 'Principal first, interest last',
      // Task 2's BULLET_LOAN (BulletLoanGenerator, IN_ARREARS only); AGENTS.md §2.3: selectable only for terms ≤ 1 year.
      BULLET_LOAN: 'Principal and interest at maturity',
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
    // R143: period-indexed IRR annualisation is not trustworthy on sparse cash flows.
    irrCaveat: {
      marker: 'Not reliable',
      tooltip:
        'This annual figure is derived by compounding a per-period return; this run’s cash flows are sparse (for example a bullet loan), so the period count used for the annualisation is wrong and the reading is not reliable. Use XIRR (the true annual rate) instead.',
    },
    tabs: {
      metrics: 'Metrics',
      schedule: 'Amortization',
      cashflow: 'Cash Flow',
      settlement: 'Early settlement',
      eir: 'Effective Interest',
    },
    // Task 6 §6 / R121: the borrower-view section title and note (binding wording —
    // never rephrased as a rate the contract charges).
    borrowerSection: {
      title: 'Borrower-side view (funding-cost indicators, not contract interest rates)',
      note:
        'These are labels of the same schedule read from the borrower-side cash-flow direction. They are funding-cost display aids: not interest the contract charges, and never summed with any lessor figure. The guaranteed residual is included at the lessor-side full amount, whereas CAS 21 第十八条 counts only the expected-payable part for the lessee.',
    },
    // R149: stamp duty is a contract tax (AGENTS.md §4) derived from the total rent; it enters
    // neither the amortization table nor the IRR cash flows.
    stampDuty: {
      title: 'Stamp duty (contract tax — not in the schedule, not in the IRR)',
      note:
        'Tax base = Σ rent − separately stated VAT, rate 0.005% (five hundredths of a per ten thousand, the separate financing-lease tax item of the Stamp Tax Law, excluding VAT). It is a contract tax derived from this run’s total rent: it does not enter the amortization table, does not enter the IRR cash flows, and is never added to Σ rent to form a new cost figure.',
      base: 'Tax base (Σ rent − VAT)',
      rate: 'Rate',
      amount: 'Duty payable',
      unavailable: 'Total rent is missing or unparseable, so stamp duty cannot be computed',
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
    tailAbsorption: 'Tail absorption: last period (default; single-point signed disclosure per P-09)',
      compositionTitle: 'Composition (principal vs interest)',
      balanceTitle: 'Balance curve',
      tableTitle: 'Amortization schedule',
    },
    settlement: {
      settlementDate: 'Settlement date',
      penaltyMode: 'Prepayment penalty',
      penaltyRate: 'By rate',
      penaltyAmount: 'Fixed amount',
      penaltyRateLabel: 'Penalty rate (annual)',
      penaltyRatePh: 'e.g. 1 or 1%',
      penaltyAmountLabel: 'Penalty amount',
      penaltyAmountPh: 'e.g. 10000',
      overdueDays: 'Overdue days',
      overdueRate: 'Overdue penalty rate (overridable)',
      overdueRatePh: 'Leave empty for no overdue penalty',
      periodNo: 'Settlement period',
      remainingPeriods: 'Remaining periods',
      remainingPrincipal: 'Remaining principal',
      accruedDays: 'Actual accrual days',
      accruedInterest: 'Accrued interest',
      penalty: 'Prepayment penalty',
      overduePenalty: 'Overdue penalty',
      savedInterest: 'Interest saved',
      prepayAmount: 'Partial prepayment amount',
      prepayPh: 'e.g. 200000',
      prepayRun: 'Recalculate remaining installments',
      prepayPaymentDateOnly: 'The recalculation must be done on a payment date (the settlement date equals some period end date)',
      settlementAmount: 'Settlement amount',
      invalid: 'Not computable: the settlement date must fall inside the term (first start date to last end date)',
      caliber: 'Caliber: interest for the actual borrowing period (Civil Code art. 677); accrued interest = remaining principal × period rate × accrual days ÷ period days, head-inclusive/tail-exclusive, to the cent (P-03/P-06); settlement = principal + accrued interest + penalty + overdue penalty — never a discount off the remaining rent total (§2.5).',
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
        DEPOSIT_INTEREST: 'Deposit interest',
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
