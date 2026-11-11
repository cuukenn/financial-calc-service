# financial-calc-web（可视化测算前端）

Vue 3 + TypeScript + Ant Design Vue 4 单页应用（Plan 3/4/5，R80–R87 与 R100–R101），经 `calc-server`
REST API 消费核心计算引擎：两个测算场景跑在同一个引擎与同一个 `POST /api/v1/calculations`
契约上，后端与核心模块零改动（房贷场景只是参数映射与前端闸门）。

## 技术栈

- Vue 3（`<script setup>` + TS）、Vite 6
- Ant Design Vue 4.x（ConfigProvider locale 随语言切换 zh_CN/en_US）
- ECharts（直接引入，无包装库；option 构造为纯函数 `src/components/charts/options.ts`）
- vitest + @vue/test-utils 组件测试；原生 fetch 封装 API client（无 Router / Pinia，最小 v1）

## 启动

前置：`calc-server` 运行于 `http://localhost:8080`（R81：Vite dev server 代理 `/api` → 8080，后端不加 CORS）。

```bash
cd web
npm install
npm run dev        # 开发模式（默认 http://localhost:5173）
```

## 命令

```bash
npm run test       # vitest 全量组件测试
npm run build      # vue-tsc 类型检查 + vite 生产构建（输出 dist/）
npm run lint       # ESLint（脚手架默认规则）
```

## 功能

- **两个测算场景（Tabs，R100）**：**租赁测算** 与 **房贷计算器**，同一个引擎、同一个
  `POST /api/v1/calculations` 契约（无新端点）。选中态记忆在 `localStorage` 的 `fcs.scenario`
  （回落默认 `lease`），非活动面板只隐藏不销毁；清空要素由两个表单自己的重置按钮负责。
  字段映射与前端闸门（首付比例 ≥ 15%、贷款成数（LTV）、DTI 50%/60%、期限 ≤ 480 月、
  到期一次还本付息仅在 ≤ 12 期可选）见仓库根 `README.md` 的「场景说明」与映射表。
- **测算表单**：全部要素字段（本金/利率/日期/期数/频率/付款时点/口径/滚动/方式/残值/保证金/宽限期），
  默认值 = 黄金要素（1M @ 6%，12 期月付，等额本息），开箱提交即复现 `TOTAL_RENT 1 032 797.15`。
  EXPLICIT_RENT 联动逐期租金编辑器；规则行编辑器支持分段费率（预设按钮文案由期数实际计算派生，
  新增行 ruleId 自动避让既有 ID）；费用编辑器。错误经统一 `{code, message}` 体原样透出 FCS 码。
- **房贷计算器表单**：贷款金额 / 贷款期限（月，≤ 480）/ 当前 LPR 与加点（0.05 个百分点步长）/
  放款日 / 首期还款日 / 抵押物成交价与评估价 / 月可支配收入与其他月债务支出 / 还款方式
  （等额本息、等额本金、到期一次还本付息仅 ≤ 12 期可选）。抵押物价值、贷款成数（LTV）、
  首付比例、DTI 是前端十进制串上的实时派生读数（阈值配色在未舍入原值上判定），
  不进 payload、不进引擎指标目录；首付比例未达 15% 时提交被阻断并清掉上一次结果。
  Task 7 实测（2026-11-09）曾发现这份 payload 在真实 `calc-server` 前被 400 拒收
  （`FCS-COM-0001: 参数 residualHandling 非法: 必填`，补上后又缺 `rateChangePolicy`）——
  缺陷 D-1，已在 Task 7 修复轮 1 按 R137 修掉：表单显式发送 `residualValue "0"` /
  `residualHandling "SEPARATE"` / `rateChangePolicy "RESOLVE"`（payload 11 → 14 键），
  服务端不为必填要素补默认（AGENTS.md §3.1）。同一 payload 由 `calc-server` 的
  `MortgagePayloadContractTest` 打给真实引擎钉住；组件测试仍是 mock 姿态，但键清单与服务端
  测试同源。此前未被发现的原因正是组件测试把 API 全 mock。
- **结果区块**（Tabs：指标 / 摊销计划 / 现金流 / 实际利率法，R84）：
  - 指标 Statistic 卡（总租金/总利息/总费用/总税额/租金率/IRR 年化/XIRR；金额字符串原样直显，
    null 指标渲染「—」；其余全量指标在下方键值表）；指标卡与指标表行头悬浮显示指标目录
    （中文名/公式/说明，对齐设计文档 §9.5 的 21 项 + R146 租赁内含利率 1 项 + R103 借款方视角 4 项，共 26 项，R86）。
  - 问题列表按级别着色：ERROR 红 / WARN 橙 / INFO 灰。
  - 摊销计划 16 列 AntD Table（横向滚动，金额列右对齐，字符串原样直显）。
  - 现金流表（期次/日期/类型/方向/金额，类型与方向 i18n 标签；方向着色 Tag：
    INFLOW 绿 / OUTFLOW 红，金额字符串原样直显）+ 累计现金流回收曲线（按日期聚合
    带符号现金流逐日累加，与零轴交点对应 PAYBACK_PERIOD 回收点，R87）。
  - 实际利率法摊销表（§9.7 六列 + EIR/利息总额调整差摘要行；IRR 未收敛时 Empty 占位）。
  - ECharts 可视化：构成堆叠柱图（每期本金 vs 利息）+ 期末余额曲线；仅图表坐标轴将金额
    字符串 parse 为 Number，展示文本不做任何数值变换。
- **国际化（R85）**：vue-i18n v9，zh-CN 默认 + en-US（键位经 `typeof zhCN` 编译期对齐 +
  运行时 parity 测试）；页头语言切换器，选择持久化 localStorage；后端 FCS 消息原样透出。

## 目录

```
web/src/
├── api/            # fetch client + DTO 类型镜像（对齐 calc-server dto）
├── components/
│   ├── CalculationForm.vue   # 租赁测算表单（三卡片分组）
│   ├── MortgageForm.vue      # 房贷计算器表单（R100/R101）
│   ├── MortgageIncomePanel.vue  # 月可支配收入 / 其他月债务支出 → DTI 读数
│   ├── mortgageRules.ts      # 房贷前端闸门纯模块（480 月、≤12 期 BULLET、5bp 步长）
│   ├── ratioStatus.ts        # 阈值读数配色与提交闸门同源判定
│   ├── editors/              # 规则行/显式租金/费用编辑器子组件
│   ├── ResultPanel.vue       # 结果区块（Tabs：指标/摊销/现金流/实际利率法）
│   ├── LanguageSwitcher.vue  # 语言切换（zh-CN/en-US）
│   ├── charts/               # EChart 薄封装 + 纯函数 option 构造
│   └── ruleIds.ts            # 规则行 ID 派生
├── i18n/            # vue-i18n（zh-CN/en-US 目录 + 指标目录）
├── styles/          # workbench.css：卡片/栅格/阈值读数配色只定义一次
├── scenario.ts      # 场景 Tab 选中态（localStorage 的 fcs.scenario）
└── App.vue          # 布局（无路由，两个场景 Tabs）
```
