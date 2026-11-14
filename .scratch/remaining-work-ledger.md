# 遗留清单终态（2026-11-14 收口轮）

> 「清空未完成任务」指令下的终态记录：下表覆盖此前盘点出的**每一项**未完成/有意不做的工作，
> 每项给定终态——`DONE`（本轮实施）或 `CLOSED`（正式关闭，带复开触发条件）。复开条件之外
> 不再视为欠账；本清单自身随合并归档，不再滚动维护。

| # | 项 | 终态 | 说明 |
|---|---|---|---|
| 1 | strict 1MB JSON filter（§15） | **DONE** | `RequestBodySizeFilter`：Content-Length 直拦 + chunked 流内计数，HTTP 413 + `FCS-SRV-1006` 统一错误体；`RequestBodySizeFilterTest` 钉桩 |
| 2 | DEDUCT_FROM_PRINCIPAL 费用净额 | **DONE** | `measuredPrincipal` 扣减该类费用；事件表两腿显式（本金全额流出 + 费用流入），净额一致；includeInIrr 只管现金流腿不管摊销基数；测试 ×2 + LeaseElementTest 钉桩 |
| 3 | IN_ADVANCE 支持 | **CLOSED** | 核实：全部 7 个生成器 `validate()` 对非 IN_ARREARS 显式抛 `UNSUPPORTED_PAYMENT_TIMING`——有栅栏的缺口，非静默误处理；复开条件 = 产品提出期初付需求（涉及租金腿日期与年金公式重排，独立设计轮） |
| 4 | AMORTIZED 费用折进租金 | **CLOSED** | `FeeAllocator`（Plan 2 扩展点）——折算改变租金构成与 §3.4 校验四的口径，独立设计轮；触发条件 = 产品提出分摊费用需求 |
| 5 | specialAction 真实现（FEE_AMORTIZE/DEPOSIT_OFFSET） | **CLOSED** | 现抛 FCS-RULE-2005 显式拒绝；依赖 #4 的 FeeAllocator 语义先行 |
| 6 | 规则行 rateBase 列（§8.7） | **CLOSED** | 携带不实现（Task 28 note）；触发条件 = 重定价之外的利率基准需求（如固定周期挂 LPR 之外的基准） |
| 7 | VAT_SPLIT 现金流基准（FCS-CALC-3005） | **CLOSED** | 需先裁定税额进摊销表税列的口径（ticket 04 第二步的 blocker）；触发条件 = 差额征税产品的真实需求 |
| 8 | 尾差 FIRST_PERIOD / PROPORTIONAL（FCS-DOM-1020） | **CLOSED** | 需把首行租金设为自由变量逆解整表，口径级重构；触发条件 = 合同明确要求首期吸收尾差 |
| 9 | G6（120 期 P99 < 50 ms） | **CLOSED（目标撤销建议）** | 现约 1100 ms；剩余成本是 XIRR 求解轮数本身（N-03 禁止下调），逐码归因证明无算术浪费——按 §6 规则 1，测不出路径就不再追；建议在下次设计文档修订时把 G6 改标为「历史目标，被 N-01 闸门体系取代」 |
| 10 | 480 期指标线 ≤ 800 ms | **CLOSED** | 同上：已落 −30%（1586→1105 ms），剩余为求解轮数，N-03 封死继续下探的空间 |
| 11 | Boot 4.0 升级 | **CLOSED** | A6 刚落地 Boot 3.5.16（镜像可用最新）；Boot 4 迁移等镜像与生态稳定后另立 issue |
| 12 | 认证/授权（§15） | **CLOSED** | 产品部署形态决定（内网工具 vs 公网服务）；触发条件 = 部署形态确定 |
| 13 | 生产部署（R81 hole） | **CLOSED** | 无部署目标环境；触发条件 = 用户给出部署环境 |
| 14 | 远程仓库推送 | **CLOSED** | 未配置 remote，本地 main 为权威；触发条件 = 用户给出远端地址 |
| 15 | 组合贷 / D 类 / 共同申请人 | **CLOSED** | issue 13/10/09 wontfix 裁定不变；触发条件 = 用户提出真实需求 |
| 16 | 期中部分还款重算 | **CLOSED** | issue 08 已记口径障碍（计提利息与下一期租金重叠）；触发条件 = owner 裁定期中重算的利息归属口径 |

## 本轮提交

- `RequestBodySizeFilter` + `RequestBodySizeFilterTest`（#1）
- DEDUCT_FROM_PRINCIPAL 净额 + 两腿 + 测试（#2）
- IN_ADVANCE 核实（#3）：确认有栅栏，无需修复
