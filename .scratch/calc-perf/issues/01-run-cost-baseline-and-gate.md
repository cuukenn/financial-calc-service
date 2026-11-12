# 01. 把「测算速度」变成可重复的基准与真闸门

Status: ready-for-agent
Type: task
Priority: P1
口径影响: 不改要素、不改口径、不改任何输出数值 —— 只加测量
Blocked by: —

## 背景

设计文档写着性能闸门 **G6：120 期 P99 < 50 ms**（`docs/superpowers/specs/2026-11-02-financial-calc-service-design.md:55`，另见 `:987-988`、`:1048`、`:1092` 提到 JMH），但全 pom 里**没有 JMH、没有任何计时测试**。唯一跑得出耗时的是 `calc-testkit` 的 `ConservationPropertyTest`（jqwik `@Property(tries = 500)`，期数 1..120，`calc-testkit/src/test/java/io/github/cuukenn/fincalc/testkit/ConservationPropertyTest.java:28-37`），surefire 报 75.55 s —— 那是属性测试的总耗时，不是闸门读数，也没人读它。

2026-11-12 用一次性探针实测（未进仓库，见 `spec.md` 的基线表）：120 期全链路 **424 ms**，其中指标装配线 389 ms；480 期 1797 ms；1200 期 + 400 段 10564 ms。**闸门被超约 8 倍，而且没有任何测试会发现这件事。**

## 要做什么

1. 在 `calc-testkit` 落一个**可重复的耗时基线测试**（不需要 JMH 也能起步：固定输入、预热轮、best-of-N 或中位数、断言阈值）。至少钉住四组形态：
   - 120 期固定利率（`THIRTY_360`，无规则集）—— 这是 G6 原闸门那一格；
   - 480 期固定利率 —— 房贷上限（UI 闸门 480 月）；
   - 480 期 + 160 段分段利率（R150 的 3 个月重定价形态）；
   - 1200 期 + 400 段 `ACT_360` —— 引擎上限 `LeaseElement.MAX_PERIODS = 1200`（`calc-domain/.../model/LeaseElement.java:26`）的最坏形态。
2. 测试要**分段计时**（schedule 生成 / 现金流表 / 指标装配线 / 校验器），否则下一次回归查不出是哪一段变慢。
3. 闸门先按**实测现状**设（120 期 ≤ 450 ms 这种「不倒退」闸门），把它标成现状闸门；**不要**第一天就把 G6 的 50 ms 写成红灯 —— G6 留到 02/03 做完再收紧，收紧那一格写进 04 的验收里（见下）。
4. 基线数字写进测试注释与本 spec，注明来源与日期，让后一次改动有对照物。

## 验收标准

- `mvn -o test` 里出现这个耗时测试，且它在当前代码上**通过**（不倒退闸门），不是红灯长挂。
- 同一台机器连跑三次，波动 < 20%（否则阈值不可信，先解决抖动再谈优化）。
- 分段计时能指出 480 期那一格的时间落在哪一段（当前应是指标装配线）。
- 测试本身不改任何生产代码，不改任何输出数值。

## 涉及文件

`calc-testkit/src/test/java/io/github/cuukenn/fincalc/testkit/`（新增耗时测试）、`.scratch/calc-perf/spec.md`（把实测表换成测试读数）。
