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
      vatRatePreset: '增值税档次：',
    },
    submit: '开始测算',
    submitting: '测算中…',
    submitFailed: '测算失败（{code}）',
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
