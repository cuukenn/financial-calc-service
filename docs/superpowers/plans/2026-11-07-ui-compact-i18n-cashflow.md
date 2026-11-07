# UI 增强：紧凑分组 + 国际化 + 指标悬浮解释 + 现金流渲染（Plan 4）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（R33 三角色）。

**Goal:** 三项用户指令的 UI 增强：①数据录入紧凑、内容大块分组；②国际化 + 指标鼠标悬浮解释；③现金流渲染（含实际利率法表）。后端零改动。

**Spec:** 后端契约不变（POST /api/v1/calculations 的 {code,message} 错误体、金额 JSON 字符串）；指标定义以设计文档 §9.5 的 21 项为准。

**范围外**: 后端任何改动、新接口、认证、移动端。

## 设计裁决

- **R84 布局**: AntD Card 大块分组 + Row/Col 栅格压缩（每行 2-4 字段，标签内联紧凑）；结果区改 Tabs（指标 / 摊销计划 / 现金流 / 实际利率法）；整体双栏（表单左、结果右）由实现者按视口判断并在报告披露。
- **R85 i18n**: vue-i18n v9，zh-CN 默认 + en-US；全部 UI 文案（表单标签/分区标题/按钮/枚举选项/结果表头/图表系列名）入 locale 目录；后端 FCS 消息原样透出不入目录；语言切换器入页头。
- **R86 指标解释**: 指标目录独立 TS 模块（code → 中文名/英文名/公式/说明），内容对齐 §9.5 的 21 项；驱动指标卡 Tooltip 与指标表 hover；领域文档资产。
- **R87 现金流**: 现金流表（期次/日期/类型/方向着色/金额字符串原样）+ 累计现金流回收曲线（ECharts，复用 parse 边界）+ 实际利率法表（EffectiveInterestRow 五列）随 Tabs 渲染。

## Tasks

### Task 1: 紧凑分组布局重构

Card 分组 + 栅格压缩 + 结果 Tabs 化（指标/摊销计划/现金流/实际利率法占位，现金流内容 Task 3 填充）；所有既有测试回归（文案断言如受布局影响同步调整，但不得删除判别力）。

### Task 2: 国际化 + 指标悬浮解释

vue-i18n v9 接入 + 全量文案迁移（zh-CN/en-US 双目录）+ 语言切换器；指标目录 TS 模块 + Statistic 卡 Tooltip + 指标表 hover（悬浮显示中文名/公式/说明）。

### Task 3: 现金流渲染 + 收尾

现金流表 + 方向着色 + 累计回收曲线；实际利率法表；README/勘误收尾（布局/i18n/指标目录/现金流四项）。

## 验收

每任务 `npm run test` + `npm run build` + `npm run lint` 三门绿；最终 web 测试数 ≥ 29 + 新增；后端 395 不变。
