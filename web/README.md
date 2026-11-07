# financial-calc-web（可视化测算前端）

Vue 3 + TypeScript + Ant Design Vue 4 单页应用（Plan 3 / R80–R82），经 `calc-server` REST API
消费核心计算引擎。后端与核心模块零改动。

## 技术栈

- Vue 3（`<script setup>` + TS）、Vite 6
- Ant Design Vue 4.x（ConfigProvider 中文 locale）
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

- **引擎状态**：ping + `GET /api/v1/algorithms` 六算法清单展示（连通性证明）。
- **测算表单**：全部要素字段（本金/利率/日期/期数/频率/付款时点/口径/滚动/方式/残值/保证金/宽限期），
  默认值 = 黄金要素（1M @ 6%，12 期月付，等额本息），开箱提交即复现 `TOTAL_RENT 1 032 797.15`。
  EXPLICIT_RENT 联动逐期租金编辑器；规则行编辑器支持分段费率（预设按钮文案由期数实际计算派生，
  新增行 ruleId 自动避让既有 ID）；费用编辑器。错误经统一 `{code, message}` 体原样透出 FCS 码。
- **结果区块**：
  - 指标 Statistic 卡（总租金/总利息/总费用/总税额/租金率/IRR 年化/XIRR；金额字符串原样直显，
    null 指标渲染「—」；其余全量指标在下方键值表）。
  - 问题列表按级别着色：ERROR 红 / WARN 橙 / INFO 灰。
  - 摊销计划 16 列 AntD Table（横向滚动，金额列右对齐，字符串原样直显）。
  - ECharts 可视化：构成堆叠柱图（每期本金 vs 利息）+ 期末余额曲线；仅图表坐标轴将金额
    字符串 parse 为 Number，展示文本不做任何数值变换。

## 目录

```
web/src/
├── api/            # fetch client + DTO 类型镜像（对齐 calc-server dto）
├── components/
│   ├── CalculationForm.vue   # 测算表单
│   ├── ResultPanel.vue       # 结果区块（指标卡/问题/表格/图表）
│   ├── EngineStatus.vue      # 引擎连通状态
│   ├── charts/               # EChart 薄封装 + 纯函数 option 构造
│   └── ruleIds.ts            # 规则行 ID 派生
└── App.vue         # 布局（无路由）
```
