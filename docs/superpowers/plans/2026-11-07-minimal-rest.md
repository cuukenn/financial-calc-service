# 最小 REST 模块实施计划（Plan 2，重定位版）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development（R33 三角色）。

**Goal:** `calc-server` Spring Boot 薄壳，把 `CalculationFacade` 暴露为 JSON API，使引擎可被系统消费。Excel 适配与持久化/版本链已剔除（勘误 2026-11-07，R74/R75）。

**Spec:** 设计文档 §10（编排时序，Excel 部分剔除）、§15（安全基线：资源限制）、§16（Spring Boot 2.7.18 / springdoc 1.7.x）；勘误节（范围变更）。

**范围外（勘误已记）**: Excel、持久化/版本链、鉴权体系（v1 仅做基础资源限制）、前端、批量、导出。

## 设计裁决

- **R74**（用户指令）：持久化/版本链延后至真实消费方存在；`PersistencePort` 插入点保留在编排第 8 步后。
- **R75**（用户指令）：Excel 适配延后待重估；映射点 `LeaseElement.explicitRents` 不变。
- **R76 DTO 边界**：核心模块不依赖 Jackson（Global Constraint）——`calc-server` 用 **DTO + 手写映射**（Request DTO → `LeaseElement.Builder`/`RuleRow`；`CalculationResult` → Response DTO）。不给领域模型加任何注解。
- **R77 异常映射**：`BusinessException` → HTTP 400 + `{"code": "FCS-…", "message": …}`；`SystemException` → 500；`FCS-ENG-1002/1003` 等请求性错误 → 400。错误体统一 `{code, message, details?}`；OpenAPI 描述错误结构。
- **R78 依赖**：Spring Boot 2.7.18 + springdoc-openapi 1.7.x 进 `calc-bom`（版本集中）；`calc-server` 依赖 `calc-engine`（传递 domain/rule）+ spring-boot-starter-web + springdoc，仅此。
- **R79 资源限制（设计 §15 最小执行）**：请求体 ≤ 1MB（server 配置）；期数 ≤ 10000 由领域校验已有（periods 上限待查——无则记 Plan 2 backlog，不在本计划新增校验）。

## Tasks

### Task 1: calc-server 模块骨架

**Files:** `calc-server/pom.xml`（parent + R78 依赖）、根 `pom.xml`（+module）、`calc-bom/pom.xml`（Boot/springdoc 版本管理）、`CalcServerApplication`（@SpringBootApplication + 组件扫描 com.fincalc.engine 等不需要——facade 手动装配为 @Bean）、`application.yml`（端口 8080、体限制）、健康检查端点 `GET /actuator/health`（spring-boot-starter-actuator）或最简 `GET /api/v1/ping`、上下文加载测试 + `CalculationFacade` @Bean 装配测试（MockMvc）。
**验收:** `mvn -o clean test` 绿（364 + 新增）；`CalculationFacade.defaults()` 作为 Bean 可注入。

### Task 2: 测算 API（POST /api/v1/calculations）

**Files:** `server/dto/CalculationRequestDto`（LeaseElement 字段平铺 + ruleRows 数组 + 显式租金数组）、`CalculationResponseDto`（runId/schedule 摘要/installments/metrics/cashFlows/issues/effectiveInterest）、手写 Assembler（DTO→Builder→LeaseElement；RuleRow 列表→`RuleCompiler`→`CompiledRuleSet`；Result→Response）、`CalculationController`、`ApiExceptionHandler`（@RestControllerAdvice，R77 映射）。
**语义:** ruleRows 非空 → `RuleCompiler(FunctionRegistry.defaults())` 编译后随请求传入；`method=EXPLICIT_RENT` 时校验 explicitRents 非空（领域层已校验，DTO 层不重复）。响应只含可序列化 DTO——`FinancialMetrics`/issues 逐字段映射。
**TDD:** MockMvc：正路（等额本息 12 期，TOTAL_RENT 1 032 797.15）；分段规则行端到端；EXPLICIT_RENT 端到端；BusinessException → 400 + code；SystemException 路径 → 500（构造触发）；DTO 映射的单测（含 IRREGULAR/explicitPeriods）。

### Task 3: OpenAPI + 算法清单 + 文档收尾

**Files:** springdoc 依赖 + `GET /api/v1/algorithms`（五种 code + 说明，来自 `RepaymentMethodId`）、`@Schema` 注解于 DTO、README（curl 示例）、设计文档勘误（R74/R75/R76–R79 若有未记）。
**TDD:** algorithms 端点测试；OpenAPI UI 可达性（/v3/api-docs 非 404）。

## 验收

全量绿（364 + 新增）；curl 可跑通正路请求；错误结构统一含 FCS 码；核心模块依赖图不变（calc-server 是唯一 Jackson/Spring 消费者，ArchUnit 既有规则不受影响——calc-server 不在守护范围，如需要加一条 calc-server 不被核心依赖的规则）。
