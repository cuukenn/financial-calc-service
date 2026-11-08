/**
 * zh-CN 文案（默认语言，R85）。键按领域分域：app / form / enums / result / charts。
 * en-US.ts 以 `typeof zhCN` 做编译期键位对齐（两目录结构必须完全一致）。
 *
 * 约定：
 * - 表单要素名称对齐设计文档 §4 统一语言与行业标准术语（R91：计息基准/日期调整等）。
 * - 枚举选项标签主文本为本地化名称，内部 code 不入标签（R89）。
 * - 后端 FCS 消息（错误体 message、算法 description）原样透出，不入本目录。
 * - vue-i18n 消息中的字面 `@` 需写作 {'@'}（链接消息保留字）。
 */
const zhCN = {
  app: {
    title: '租赁测算工作台',
    footer: 'financial-calc-service · 可视化测算前端（Plan 3/4）',
    // R100：同一页面的两个测算场景（共用一个引擎与一个 POST /api/v1/calculations 契约）。
    scenario: {
      lease: '融资租赁',
      mortgage: '房贷计算器',
    },
  },
  form: {
    cardTitle: '测算表单',
    basic: {
      // R91：要素名对齐 §4 统一语言（租赁要素/计息基准）与行业惯例（支付频率等）。
      title: '租赁要素',
      principal: '租赁本金',
      principalPh: '如 1000000',
      nominalRate: '名义年利率',
      // R92-6：百分数口径输入（6 或 6% → 0.06，600bp → 0.06），提交前由表单层换算为小数字符串。
      nominalRatePh: '如 6% 或 600bp',
      commenceDate: '起租日',
      firstRentDate: '首期租金支付日',
      periods: '期数',
      currency: '币种',
      // R92-2：常见币种下拉（标签「中文名（码）」，提交值为 ISO-4217 码）。
      currencies: {
        CNY: '人民币（CNY）',
        USD: '美元（USD）',
        EUR: '欧元（EUR）',
        HKD: '港币（HKD）',
        JPY: '日元（JPY）',
        GBP: '英镑（GBP）',
        SGD: '新加坡元（SGD）',
      },
      frequency: '支付频率',
      timing: '支付时点',
      dayCountCode: '计息基准',
      dateRollCode: '日期调整',
      rateChangePolicy: '利率变更策略',
      method: '还款方式',
    },
    rentStructure: {
      title: '租金结构',
      ruleRows: {
        title: '规则行（可选：分段利率）',
        hint: '对全部方式开放：携带规则行即启用分段利率（RULE_DRIVEN 则为必填规则集）',
        ruleIdPh: '规则 ID',
        priorityPh: '优先级',
        fromPeriodPh: '起始期',
        toPeriodPh: '截止期',
        // R92-6：规则行年利率同为百分数口径（3 / 3% / 300bp → 0.03）。
        annualRatePh: '年利率（如 3% 或 300bp）',
        // R95（评审）：行内即时格式错误 + 提交层逐行阻断文案。
        rateFormat: '利率格式无效（示例：3 / 3% / 300bp）',
        rateFormatRow: '分段利率第 {n} 行格式无效（示例：3 / 3% / 300bp）',
        remove: '删除',
        add: '+ 添加规则行',
        preset: "分段费率预设（{from}-{to} 期 {'@'}3%，{from2}-{to2} 期 {'@'}6%）",
      },
      explicitRents: {
        label: '逐期租金（共 {periods} 期，每期须大于 0）',
        periodPh: '第 {n} 期',
        incomplete: '显式租金方式须填满全部 {periods} 期租金且每期大于 0',
      },
      grace: {
        title: '宽限期（可选）',
        interestOnlyPeriods: '只付息期数',
        fullGracePeriods: '全免期数',
      },
    },
    feesOther: {
      title: '费用与其他',
      fees: {
        title: '费用（可选）',
        typePh: '费用类型',
        amountPh: '金额',
        remove: '删除',
        add: '+ 添加费用',
      },
      // R91：§9.6 语义 —— SEPARATE 下该金额即留购价，BALLOON_IN_RENT 下为气球残值。
      residualValue: '残值 / 留购价',
      residualHandling: '残值处理',
      deposit: '保证金',
      vatRate: '增值税率',
      // R92-5：百分数口径输入（6 或 6% → 0.06），附中国增值税档次快选。
      vatRatePh: '如 6 或 6%',
      vatRateFormat: '格式无效：请输入如 6 或 6% 的增值税率',
    },
    submit: '开始测算',
    submitting: '测算中…',
    submitFailed: '测算失败（{code}）',
    // Task 6 §2：显式重置（两个表单各有一个重置按钮，共用这条文案）。
    reset: '重置表单',
    validation: {
      principal: '请输入租赁本金',
      nominalRate: '请输入名义年利率',
      nominalRateFormat: '格式无效：请输入如 6% 或 600bp 的名义年利率',
      commenceDate: '请选择起租日',
      firstRentDate: '请选择首期租金支付日',
      periods: '请输入期数',
      frequency: '请选择支付频率',
      timing: '请选择支付时点',
      dayCountCode: '请选择计息基准',
      dateRollCode: '请选择日期调整规则',
      method: '请选择还款方式',
    },
  },
  mortgage: {
    // Task 5 / R101：房贷场景走同一个 POST /api/v1/calculations 契约；
    // 术语一律取 AGENTS.md §2.2 规范名（贷款金额/首付比例/贷款成数（LTV）/
    // 月房贷支出/收入比/月所有债务支出/收入比），不得同义替换。
    cardTitle: '房贷测算表单',
    basic: {
      title: '贷款要素',
      principal: '贷款金额',
      principalPh: '如 2380000',
      lpr: '当前 LPR（%）',
      // P-04：本项目无 LPR 数据源，LPR 由用户按百分数口径自填（3.5 = 3.5%）。
      lprPh: '如 3.5',
      spread: '加点（bp）',
      // P-04：LPR 报价步长 0.05 个百分点 = 5bp，加点只接受 5bp 整数倍。
      spreadPh: '如 35（须为 5bp 的整数倍）',
      composedRate: '年利率（LPR + 加点）',
      composedRateHint: '提交给引擎的年化小数字利率（P-02）',
      composedRateInvalid: '无法解析，请检查 LPR 与加点',
      commenceDate: '放款日',
      firstRepaymentDate: '首期还款日',
      periods: '贷款期限（月）',
      method: '还款方式',
    },
    collateral: {
      // R103：LTV/首付为前端派生指标，不进引擎、不进指标目录。
      title: '抵押物与杠杆（前端计算，不进引擎）',
      transactionPrice: '抵押物成交价',
      appraisalValue: '评估价',
      collateralValue: '抵押物价值（成交价与评估价孰低）',
      ltv: '贷款成数（LTV）',
      downPaymentRatio: '首付比例',
    },
    income: {
      // R103：DTI 亦为前端派生指标。
      title: '收入与债务（前端计算，不进引擎）',
      monthlyIncome: '月可支配收入',
      monthlyIncomePh: '如 16000',
      otherMonthlyDebt: '其他月债务支出',
      otherMonthlyDebtPh: '如 1600',
      monthlyPayment: '月供',
      dti: '月房贷支出/收入比',
      allDebtRatio: '月所有债务支出/收入比',
      // AGENTS.md §9.5 姿态：不可计算的指标不得静默取 0，必须说明不可计算。
      unavailable: '未提供收入，无法判断 DTI',
      thresholds:
        '阈值与配色：月房贷支出/收入比 ≤ 50% 合规，> 50% 接近上限，> 60% 超限；月所有债务支出/收入比 ≤ 60% 合规，> 60% 超限；恰好等于阈值不视为超限（含本数），判限用未舍入原值。',
      paymentNote: '月供口径：等额本息取首期租金；等额本金与到期一次还本付息取全表最大租金（最坏月份）。',
    },
    status: {
      // Task 6 §3：每个状态一条文案标记，语义不只靠颜色传达（颜色与标记同为断言对象）。
      ok: '合规',
      warn: '接近上限',
      breach: '超限',
      // 下限类读数（首付比例）的 breach 方向相反：低于下限，不是超限。
      belowFloor: '低于下限',
      neutral: '无法判断',
    },
    gates: {
      // §71 硬约束：这些 [法] 门槛由前端校验，文案不得声称后端已校验（R107/R109）。
      note: '前端门槛：首付 ≥ 15%、期限 ≤ 480 个月、DTI 50%/60% 均由本表单校验，测算引擎不校验这些门槛。',
      bulletOnly: '「到期一次还本付息」仅在贷款期限 ≤ {max} 个月时可选（金规〔2026〕5号：期限 ≤ 1 年可到期一次还本付息）。',
      bulletReset: '贷款期限超过 {max} 个月：「到期一次还本付息」不可选，已重置为等额本息（前端规则，引擎未校验）。',
      downPaymentFloor: '首付比例低于下限 {min}（当前 {actual}）：已阻止提交（该下限由前端校验，引擎未校验）。',
      // AGENTS.md §2.3「阈值即硬约束」：不可判定的下限不得静默放行。
      downPaymentFloorNotEvaluable:
        '抵押物价值不大于 0（成交价与评估价孰低），贷款成数（LTV）与首付比例无法计算：首付比例 ≥ {min} 的下限无法判定，已阻止提交（该下限由前端校验，引擎未校验）。',
      rateNotComposable: '无法由 LPR 与加点组合出年利率，请检查输入。',
    },
    validation: {
      principal: '请输入贷款金额',
      // R101：principal 是正十进制串 —— '0' 与负数在这里就拦住，不做无谓的引擎往返。
      principalPositive: '贷款金额须大于 0（本金为 0 或负数不是合法的测算要素）',
      money: '金额格式无效：请输入非负十进制数（如 2380000）',
      lpr: '请输入当前 LPR',
      lprFormat: 'LPR 格式无效：请输入百分数口径的十进制数（如 3.5）',
      spread: '请输入加点',
      spreadStep: '加点必须是 5bp 的整数倍（P-04：LPR 报价步长 0.05 个百分点），不做静默取整',
      commenceDate: '请选择放款日',
      firstRepaymentDate: '请选择首期还款日',
      firstRepaymentBeforeCommence: '首期还款日不得早于放款日（引擎会拒绝该组合）',
      periods: '请输入贷款期限（月）',
      periodsRange: '贷款期限须在 1–{max} 个月之间（≤ 40 年）',
      transactionPrice: '请输入抵押物成交价',
      appraisalValue: '请输入评估价',
    },
  },
  enums: {
    // R89：下拉主文本一律为本地化名称（纯中文）；内部 code 不再出现在标签中
    // （计息基准例外：行业标准斜杠记法为主文本，code 仅作下拉项次要小字）。
    frequency: {
      MONTHLY: '月',
      QUARTERLY: '季',
      SEMI_ANNUAL: '半年',
      ANNUAL: '年',
      IRREGULAR: '不规则',
    },
    timing: {
      IN_ARREARS: '期末付',
      IN_ADVANCE: '期初付',
    },
    dayCount: {
      THIRTY_360: '30/360',
      THIRTY_E_360: '30E/360',
      ACT_360: 'ACT/360（实际/360）',
      ACT_365F: 'ACT/365F（实际/365固定）',
      ACT_365_25: 'ACT/365.25（实际/365.25）',
      ACT_ACT: 'ACT/ACT（实际/实际）',
    },
    // R91：日期调整选项用行业惯例动词（顺延/提前），NONE 对齐「日期调整」字段名。
    dateRoll: {
      NONE: '不调整',
      FOLLOWING: '顺延',
      MODIFIED_FOLLOWING: '修正顺延',
      PRECEDING: '提前',
      MODIFIED_PRECEDING: '修正提前',
      END_OF_MONTH: '月末对齐',
    },
    residualHandling: {
      SEPARATE: '期末单独支付（留购价）',
      BALLOON_IN_RENT: '摊入租金（气球本金）',
    },
    rateChangePolicy: {
      RESOLVE: '自变更日重算',
      FIXED_RENT: '租金不变，尾差吸收',
      DRIFT: '差额滚入期末余额',
    },
    chargeMode: {
      UPFRONT: '期初一次性',
      DEDUCT_FROM_PRINCIPAL: '抵减本金',
      AMORTIZED: '摊入租金',
    },
    method: {
      EQUAL_INSTALLMENT: '等额本息',
      EQUAL_PRINCIPAL: '等额本金',
      INTEREST_FIRST: '先息后本',
      PRINCIPAL_FIRST: '先本后息',
      // Task 2 的 BULLET_LOAN（BulletLoanGenerator，仅接受 IN_ARREARS）；§2.3 期限 ≤ 1 年才可选。
      BULLET_LOAN: '到期一次还本付息',
      RULE_DRIVEN: '规则驱动',
      EXPLICIT_RENT: '显式租金',
    },
  },
  result: {
    cardTitle: '测算结果',
    runId: 'runId：{runId}',
    // R90a/R90b：数值显示格式化后，原始终端字符串随 hover tooltip 透出（对账不丢精度）。
    raw: '原值：{value}',
    hasErrors: '测算结果包含 ERROR 级问题，请检查下方问题列表',
    tabs: {
      metrics: '指标',
      schedule: '摊销计划',
      cashflow: '现金流',
      eir: '实际利率法',
    },
    issues: {
      title: '问题列表',
      period: '（第 {n} 期）',
      levels: {
        INFO: '提示',
        WARN: '警告',
        ERROR: '错误',
      },
    },
    scheduleTab: {
      compositionTitle: '构成（本金 vs 利息）',
      balanceTitle: '余额曲线',
      tableTitle: '摊销计划',
    },
    cashflowTab: {
      tableTitle: '现金流表',
      curveTitle: '累计现金流回收曲线',
      empty: '暂无现金流数据',
      sequence: '期次',
      date: '日期',
      type: '类型',
      direction: '方向',
      amount: '金额',
      types: {
        PRINCIPAL: '本金',
        RENT: '租金',
        FEE: '费用',
        RESIDUAL: '残值',
        DEPOSIT: '保证金',
      },
      directions: {
        INFLOW: '流入',
        OUTFLOW: '流出',
      },
    },
    eir: {
      summary: '实际利率（EIR）：{eir} · 利息总额调整差：{diff}',
      empty: 'IRR 未收敛或不可建表，暂无实际利率法摊销表',
    },
    table: {
      periodNo: '期次',
      startDate: '开始日',
      endDate: '结束日',
      days: '天数',
      openingBalance: '期初余额',
      principalDue: '本金',
      interestAccrued: '利息（计提）',
      interestPaid: '利息（支付）',
      fee: '费用',
      tax: '税',
      rentDue: '租金',
      closingBalance: '期末余额',
      cumulativePrincipal: '累计本金',
      cumulativeInterest: '累计利息',
      appliedRate: '适用利率',
      memo: '备注',
      eirOpeningNetInvestment: '期初净投资',
      eirInterestIncome: '利息收入',
      eirCashReceived: '现金流入',
      eirPrincipalRecovery: '本金回收',
      eirClosingNetInvestment: '期末净投资',
    },
  },
  charts: {
    principal: '本金',
    interest: '利息',
    periodLabel: '第 {n} 期',
    periodAxis: '期次',
    amount: '金额',
    closingBalance: '期末余额',
    cumulativeCashflow: '累计现金流',
    dateAxis: '日期',
  },
}

export default zhCN
