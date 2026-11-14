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
      assetValue: '资产价值（公允价值）',
      assetValuePh: '选填；填后融资额自动派生',
      financedPrincipal: '融资额：{value}',
      downPayment: '首付款',
      downPaymentPh: '如 400000',
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
      firstPeriodStub: '首期按整月+零头计息（129号①）',
      firstPeriodStubHint: '默认关闭：首期按年利率 × 期间占比一段计（129号②）',
      firstPeriodStubNote: '首期窗口拆为「整月（月利率）+ 零头天数（日利率 = 年利率 ÷ 360）」两段计息，算法在单笔内不与②混用；结果区首期行会标注口径。',
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
        vatRatePh: '税率 如 6 或 6%',
        includeIrr: '计入 IRR',
        title: '费用（可选）',
        typePh: '费用类型',
        amountPh: '金额',
        remove: '删除',
        add: '+ 添加费用',
      },
      // §2.1 / M-01：这个字段就是**留购价（名义货价）** —— 承租人期末取得所有权的对价，
      // 是真实现金流。残值（未担保余值）不参与本口径的现金流，唯一消费者是租赁内含利率
      // （CAS 21），该指标尚未实现，故不设该字段。
      residualValue: '留购价（名义货价）',
      residualValueHint: '期末承租人取得租赁物所有权时支付的对价；无留购安排填 0',
      residualHandling: '留购价处理',
      deposit: '保证金',
      depositTiming: '保证金收取时点',
      depositSettlement: '退还/抵扣方式',
      depositSettlementHint: '抵减本金：租金按「合同本金 − 保证金」测算；冲抵末期租金：保证金到期冲抵最后一期租金',
      depositInterestRate: '保证金年利率（计息）',
      depositInterestRatePh: '如 3 或 3%',
      depositInterestRateHint: '按实际占用天数计息（ACT/360，算头不算尾，单利），利息单独成行；抵减本金方式不得计息',
      amountPh: '如 0',
      initialDirectCosts: '初始直接费用（IDC）',
      guaranteedResidual: '担保余值',
      guaranteedResidualHint: '期末实际收取的担保金额（CAS 21 租赁收款额），进现金流表',
      unguaranteedResidual: '未担保余值',
      unguaranteedResidualHint: '只参与租赁内含利率的求解，不产生现金流',
      vatRate: '增值税率',
      // R92-5：百分数口径输入（6 或 6% → 0.06），附中国增值税档次快选。
      vatRatePh: '如 6 或 6%',
      vatRateFormat: '格式无效：请输入如 6 或 6% 的增值税率',
    },
    submit: '开始测算',
    submitting: '测算中…',
    chainRequiresAssetValue: '填写首付款 / 初始直接费用 / 未担保余值时，必须同时填写资产价值',
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
      // Task 6 §5（Task 5 交接的文案所有权）：到期一次还本付息的那笔钱是一次性偿还额。
      // 行头按 §2.2 的规范名体系另起名，不得冒充「月供」；算术与 DTI 口径不变。
      lumpSumPayment: '到期一次性偿还额',
      lumpSumBasis:
        '到期一次还本付息口径：这笔金额是到期一次性偿还的本金与全部递延利息之和（取全表最大期），不是月供——§2.2 的月供口径只适用于按月还本付息的方式。',
      lumpSumProxyNote:
        '把这笔到期一次性偿还额与月可支配收入直接相比，是刻意保守的代理口径：比值远大于按月摊还的真实负担，只用于提示该方式在收入维度上的风险。',
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
    // R150：重定价是房贷合同里最常变的要素（AGENTS.md §2.2 规范名「重定价日 / 重定价周期」）。
    // 落地方式是把「周期 + 每段 LPR」写成引擎已有的分段利率规则行，不新增契约键。
    repricing: {
      cycle: '重定价周期',
      hint: '不重定价 = 固定利率路径。选 3 / 6 / 12 个月（央行公告〔2024〕第 11 号只给这三个周期；该取值闸门在前端，引擎只看见分段利率）。',
      cycles: {
        none: '不重定价（固定利率）',
        months: '{months} 个月',
      },
      anchorDate: '重定价日',
      anchorPh: '留空 = 放款日对日',
      futureLprs: '后续各段 LPR（%）',
      futureLprsPh: '如 3.9, 3.1（按段顺序，逗号分隔）',
      futureLprsHint:
        '按段顺序给出每次重定价时的 LPR；加点不变（P-04：加点是合同条款，重定价只重定 LPR）。缺失的段不会被静默填成当前 LPR。',
      reuse: '缺段沿用当前 LPR（假设，非合同事实）',
      readoutTitle: '分段利率（提交前可核对）',
      currentRate: '当期执行利率',
      currentRateFrom: '自',
      nextDate: '下次重定价日',
      nextDateNone: '期限内不再重定价',
      segmentRange: '第 {from}–{to} 期（{date} 起）',
      rateMissing: '缺该段 LPR',
      assumedNotice:
        '其中 {count} 段的 LPR 是按「沿用当前 LPR」的假设补上的：它不是合同里给过的利率，结果按该假设计（AGENTS.md 无约定时按口径执行并标注）。',
    },
    gates: {
      // §71 硬约束：这些 [法] 门槛由前端校验，文案不得声称后端已校验（R107/R109）。
      note: '前端门槛：首付 ≥ 15%、期限 ≤ 480 个月、DTI 50%/60% 由本表单拦截；引擎在 product=MORTGAGE 下以 WARN 读数披露首付/期限两条（DTI 留在前端：引擎无收入字段，M-01）。',
      bulletOnly: '「到期一次还本付息」仅在贷款期限 ≤ {max} 个月时可选（金规〔2026〕5号：期限 ≤ 1 年可到期一次还本付息）。',
      bulletReset: '贷款期限超过 {max} 个月：「到期一次还本付息」不可选，已重置为等额本息（前端规则，引擎未校验）。',
      downPaymentFloor: '首付比例低于下限 {min}（当前 {actual}）：已阻止提交（该下限由前端拦截；引擎同样以 WARN 读数披露——issue 15/A2，不拦截只披露）。',
      // AGENTS.md §2.3「阈值即硬约束」：不可判定的下限不得静默放行。
      downPaymentFloorNotEvaluable:
        '抵押物价值不大于 0（成交价与评估价孰低），贷款成数（LTV）与首付比例无法计算：首付比例 ≥ {min} 的下限无法判定，已阻止提交（该下限由前端拦截；引擎仅在要素可判定时以 WARN 披露）。',
      rateNotComposable: '无法由 LPR 与加点组合出年利率，请检查输入。',
      // R150：重定价的三道闸门，全部在前端（引擎不认识「重定价周期」这个词，只认分段利率）。
      repricingCycle:
        '重定价周期只能是 3 / 6 / 12 个月（央行公告〔2024〕第 11 号）：已阻止提交（该取值由前端校验，引擎未校验）。',
      repricingCoverage:
        '重定价分段没有连续覆盖第 1 期到第 {periods} 期：已阻止提交（引擎的 RuleSetValidator 同样会拒绝缺口，这里不让它跑一趟网络）。',
      repricingMissingLpr:
        '有 {count} 段没有给出 LPR：不得静默沿用首期 LPR（AGENTS.md §3.1 输入要素缺失即报错）。请补齐后续 LPR，或显式勾选「沿用当前 LPR」并把它当作假设。',
      // ticket 08：部分提前还款重算的两道闸门。
      prepayNotPartial: '部分还款金额须大于 0 且小于剩余本金（≥ 剩余本金是一次性结清，请直接看结清金额）。',
      prepayNoPeriods: '剩余期数不足：重算需要至少 1 期。',
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
      // R150：重定价三要素的字段级校验。
      repricingCycle: '重定价周期须是 0（不重定价）或 3 / 6 / 12 个月',
      repricingAnchorBeforeCommence: '重定价日不得早于放款日（早于放款日的重定价事件不属于这笔贷款）',
      futureLprs: '后续 LPR 格式无效：请输入逗号分隔的非负百分数（如 3.9, 3.1）',
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
    depositTiming: {
      UPFRONT: '起租日收取',
      WITH_FIRST_RENT: '随首期租金',
    },
    depositSettlement: {
      REFUND: '到期退还',
      OFFSET_PRINCIPAL: '抵减租赁本金',
      OFFSET_FINAL_RENT: '冲抵末期租金',
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
    feeOwner: {
      LESSEE: '承租人承担',
      LESSOR: '出租人承担',
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
    // R143：稀疏现金流下按「期」年化的 IRR 读数不可信 —— 只标注，不改引擎口径。
    irrCaveat: {
      marker: '读数存疑',
      tooltip:
        '该年化读数是把「期」收益率折算成年得到的；本次测算的现金流稀疏（如到期一次还本付息），折算所依据的期数不正确，读数不可信。请以 XIRR（真实年化利率）为准。',
    },
    tabs: {
      metrics: '指标',
      schedule: '摊销计划',
      cashflow: '现金流',
      settlement: '提前结清试算',
      eir: '实际利率法',
    },
    // Task 6 §6 / R121：借款方视角一节的标题与说明（绑定文案，不得改写成「合同利率」）。
    borrowerSection: {
      title: '借款方视角（资金成本指标，非合同利率）',
      note:
        '这一组是同一张摊销表在借款方现金流方向上的标注，属资金成本的展示辅助：不是合同收取的利率，也不与任何出租方金额相加。还款总额里的担保余值按出租方全额口径计入，CAS 21 第十八条对承租方只计预计应付部分。',
    },
    // R149：印花税是合同税费（AGENTS.md §4），派生自 Σ租金，不进摊销表也不进 IRR。
    stampDuty: {
      title: '印花税（合同税费，不进摊销表 / 不进 IRR）',
      note:
        '计税依据 = Σ租金 − 单列增值税，税率 0.005%（万分之零点五，《印花税法》融资租赁合同独立税目，不含单列增值税）。它是由本次测算的租金总额派生出来的合同税费：不进摊销表、不进 IRR 现金流，也不与 Σ租金相加构成新的成本口径。',
      base: '计税依据（Σ租金 − 增值税）',
      rate: '税率',
      amount: '应纳税额',
      unavailable: '租金总额缺失或不可解析，印花税不可计算',
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
    tailAbsorption: '尾差吸收：末期（默认，P-09 单点带符号披露，见「尾差调整」列/表头差额）',
      compositionTitle: '构成（本金 vs 利息）',
      balanceTitle: '余额曲线',
      tableTitle: '摊销计划',
    },
    settlement: {
      settlementDate: '结清日',
      penaltyMode: '违约金',
      penaltyRate: '按比例',
      penaltyAmount: '固定金额',
      penaltyRateLabel: '违约金比例（年）',
      penaltyRatePh: '如 1 或 1%',
      penaltyAmountLabel: '违约金金额',
      penaltyAmountPh: '如 10000',
      overdueDays: '罚息逾期天数',
      overdueRate: '罚息年利率（可覆盖）',
      overdueRatePh: '留空则不计罚息',
      periodNo: '结清期次',
      remainingPeriods: '剩余期数',
      remainingPrincipal: '剩余本金',
      accruedDays: '实际计息天数',
      accruedInterest: '应计利息',
      penalty: '提前还款违约金',
      overduePenalty: '罚息',
      savedInterest: '节省利息',
      prepayAmount: '部分提前还款金额',
      prepayPh: '如 200000',
      prepayRun: '重算剩余期供',
      prepayPaymentDateOnly: '部分还款重算须在还款日当日办理（结清日 = 某期期末日）',
      settlementAmount: '结清金额',
      invalid: '无法计算：结清日须落在租期内（首期起始日至末期期末日）',
      caliber: '口径：按实际借款期间计息（民法典第 677 条）；应计利息 = 剩余本金 × 该期期利率 × 实际计息天数 ÷ 该期天数，算头不算尾、算至分（P-03/P-06）；结清金额 = 剩余本金 + 应计利息 + 违约金 + 罚息，不按剩余期数租金总额打折（§2.5）。',
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
        DEPOSIT_INTEREST: '保证金利息',
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
