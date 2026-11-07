# Vue3 + Ant Design 可视化测算前端实施计划（Plan 3，重定位版）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（R33 三角色）。

**Goal:** 基于 Vue 3 脚手架 + Ant Design Vue 构建可视化测算界面，经 `calc-server` REST API 消费引擎。持久化/Excel/关联优化已移出计划（勘误 2026-11-07）。

**Spec:** 设计文档勘误节（范围变更行）；后端契约 = `calc-server` 实际 API（POST /api/v1/calculations、GET /api/v1/algorithms、统一 {code,message} 错误体、金额 JSON 字符串）。

**范围外**: 持久化/版本链、Excel、认证/限流、多页路由/状态库（最小 v1 不需要）、移动端适配。

## 设计裁决

- **R80 技术栈与位置**: 前端置于仓库内 `web/` 目录（独立 package.json，`node_modules/`、`dist/` 进 .gitignore）；Vite + Vue 3 + TypeScript + Ant Design Vue 4.x；Node 22 已就绪。后端与核心模块零改动。
- **R81 跨域**: Vite dev server 代理 `/api` → `http://localhost:8080`，后端不加 CORS；生产部署形态延后。
- **R82 最小 v1 形态**: 单页应用（无 Router、无 Pinia）；原生 fetch 封装的 API client；表单 + 结果两个区块；ECharts 仅用于任务 3 的构成/余额曲线可视化。
- **R83 质量门**: vitest + @vue/test-utils 组件测试；`npm run build` 必须成功；沿用脚手架默认 ESLint；每个任务结束 `npm run test` + `npm run build` 双绿。

## Tasks

### Task 1: 脚手架与 API 连通

**Files:** `web/`（Vite + Vue3 + TS 脚手架）、`.gitignore` 追加、API client（fetch 封装：getAlgorithms/calculate，类型定义对齐后端 DTO：金额字符串、{code,message} 错误）、基础布局（AntD ConfigProvider 中文 locale）、「引擎状态」页块（ping + algorithms 列表展示，证明连通）。
**验收:** `npm run dev` 下页面可展示六算法清单（经代理调真实后端）；`npm run test`、`npm run build` 绿。

### Task 2: 测算表单与 API 集成

**Files:** 测算表单组件（要素字段：本金/利率/日期/期数/频率/付款时点/口径/滚动/方式/残值/保证金/宽限期；方式选择联动：EXPLICIT_RENT 显租金编辑器、RULE_DRIVEN/内置+规则集分段费率行编辑器——AntD Table 可编辑行或 Form.List）、提交 → POST /api/v1/calculations、错误展示（{code,message} Alert，FCS 码原样透出）。
**验收:** 等额本息 12 期默认表单提交返回 1 032 797.15；分段费率与显式租金两路径均可经 UI 走通；错误路径（本金 0）展示 FCS-DOM-1001。

### Task 3: 结果可视化 + 收尾

**Files:** 结果区块：指标卡（总租金/总利息/IRR/XIRR/久期，金额字符串直显）、摊销计划表（AntD Table，期次/期初/本金/利息/租金/余额）、ECharts 组成堆叠柱图（本金 vs 利息）+ 余额曲线、README（web/ 使用说明：npm run dev 前置 calc-server）、勘误查漏。
**验收:** 三任务全绿；README 双端启动说明完整。

## 验收

`npm run test` + `npm run build` 绿（每任务）；后端测试数不变（395，后端零改动——除非勘误需要）。
