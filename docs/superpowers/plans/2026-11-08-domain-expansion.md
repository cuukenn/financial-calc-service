# Plan 5：计算领域扩展与工作台升级（场景 Tab / 多视角指标 / 合规边界）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（R33 三角色，批量任务单评审）。

**Goal:** 六项用户指令（2026-11-08）全部落地：①前端 UI 调整（操作/展示/风格）；②UI 以 Tab 扩展计算场景（房贷计算器）；③指标扩展（承租人/融资方、出租人/投资方、借方/贷方视角 + 标准指标）；④后端高内聚低耦合；⑤Excel 与持久化明确移出（再次确认）；⑥补充计算边界、fail-fast 异常数据。

**Spec:** AGENTS.md（测算标准硬约束，已批准）§2.2 个人住房贷款、§2.3 监管阈值、§3.1/3.4；设计文档勘误节。

## 设计裁决

- **R100 场景范围**: v1 两 Tab——「融资租赁」（现有全量工作台）+「房贷计算器」（新增）；通用分期延后。场景 Tab 只切换表单与文案，**共享同一引擎与 REST 契约**（不新增后端接口）。
- **R101 房贷要素映射**: 贷款金额→principal；LPR + 加点(bp) → 前端合成为年利率小数（后端收小数，R92-6 换算复用）；期限 ≤ **480 月**（AGENTS.md §2.3 房贷 ≤40 年）；还款方式 = 等额本息/等额本金/**到期一次还本付息（≤1 年，即 ≤12 期月供才可选）**；首付/房产价值 → 前端 LTV 仪表（= 贷款金额 ÷ min(成交价, 评估价)，AGENTS.md §2.2）；月收入 → 前端 DTI 仪表（= 月供 ÷ 月可支配收入，阈值 50%/60% 着色）；留购价/保证金/计息基准在房贷 Tab 隐藏（后端默认值不变）。
- **R102 到期一次还本付息（BULLET_LOAN）**: 新 `RepaymentMethodId.BULLET_LOAN` + 生成器——利息逐期计提（interestAccrued）、**递延不支付**（interestPaid 0）、到期一次还本 + 全部递延利息；约束：IN_ARREARS only（对齐 R16/R20 族）；顺序在 PRINCIPAL_FIRST 之后（复用其递延机制模式）。验证：AGENTS.md §2.2「到期一次还本付息」为房贷合法方式（≤1 年）。
- **R103 视角化指标**: `MetricContext` 增加 perspective（默认 LESSOR 兼容）；LESSEE/借款方 = 现金流符号翻转后的 IRR 族（借款方 IRR = 融资成本率）+ 还款总额；指标目录扩展借款方标准指标（月供、还款总额、利息总额已有对应、DTI/LTV 为前端仪表不入引擎）。输出以 `perspective` 前缀区分（如 `BORROWER_IRR_ANNUAL`）。
- **R104 输入边界 fail-fast（item 6）**: 领域校验新增——periods ≤ **1200**（月频 100 年硬上限；房贷 UI 另限 480）、fees amount ≥ 0、deposit ≥ 0、explicitRents 已有；新码 FCS-DOM-1008 之后顺延；错误即拒（AGENTS.md §3.1 缺失即报错精神）。§15 的 10000 期上限由本上限覆盖，勘误记入。
- **R105 后端内聚 (item 4)**: TOLERANCE 五处 → 共享常量（calc-domain 支持包内单一来源）；3001/3002/3004 行内字面量回填 `CalcErrorCode` 枚举；校验器顺序注册梳理。纯重构，行为不变，测试全绿为准绳。
- **R106 Excel/持久化**: 再次确认移出（errata 已记，本计划不涉及）。

## Tasks（顺序执行）

### Task 1: 输入边界 fail-fast（R104）
LeaseElement 校验新增 periods ≤1200（FCS-DOM-1008 段）、fees ≥ 0、deposit ≥ 0（新码顺延）；测试红先；勘误行（§15 10000 期上限由 1200 期取代）。

### Task 2: 到期一次还本付息 BULLET_LOAN（R102）
RepaymentMethodId + 生成器（复用递延利息模式）+ 注册 + facade 路由 + 测试（正路/递延利息合计/IN_ADVANCE 拒绝）。

### Task 3: 视角化指标（R103）
MetricContext.perspective + LESSEE 翻转 + 借款方 IRR 族/还款总额指标 + 目录扩展 + 测试。

### Task 4: 后端内聚抛光（R105）
TOLERANCE 共享常量 + 3001/3002/3004 回填 CalcErrorCode + ValidatorChain 注册梳理；纯重构。

### Task 5: 场景 Tab 架构 + 房贷计算器表单（R100/R101）
web 场景 Tabs（融资租赁 | 房贷计算器）；房贷表单（贷款金额、LPR+加点、期限月 ≤480、三方式含 BULLET、首付/房价→LTV、月收入→DTI 仪表 50%/60% 着色、税率复用）；结果复用 ResultPanel。

### Task 6: 结果展示与风格调整（item 1）
房贷结果文案/监管仪表配色（DTI/LTV 阈值色）、操作流打磨（Tab 记忆、表单重置）、风格统一（间距/对齐）。

### Task 7: 收尾
README（场景说明）、设计文档勘误（R100–R106、§15 上限取代）、AGENTS.md 对齐检查（§2.2 到期一次还本付息已实现注记）、全量验证。

## 验收

后端测试全绿（403 + 新增）；web 测试/build/lint 三门绿；浏览器实测：房贷计算器 Tab 完整走通（含到期一次还本付息 ≤12 期）、DTI/LTV 仪表、两场景切换。
