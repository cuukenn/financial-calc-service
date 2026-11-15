# 参与贡献

感谢关注本项目。提交贡献前请先读完 [AGENTS.md](AGENTS.md)（agent 行为纪律）与
[README.md](README.md)（口径要点）。

## 环境要求

- 构建/测试：JDK ≥ 17（`JAVA_HOME` 指向 17+），`mvn -o test` 全模块闸门
- 前端：Node 20，`cd web && npm ci`

## 分支与提交

- 分支：`master` 为集成分支，功能改动请开特性分支后 PR
- 提交信息：`类型(范围): 摘要`（类型 = feat/fix/test/docs/refactor/build/chore）

## 提交前自检

1. `mvn -o test` 全模块通过（含 `RunCostBaselineTest` 性能闸门）
2. `web` 下 `npm test` 通过
3. PR 模板自检清单逐项勾选（见 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)）

## 口径红线（PR 会被驳回的情形）

- 新增输入字段未回答「它改变哪一期现金流或余额」（AGENTS.md 纪律 1，M-01）
- 对外指标不可由输入与摊销表复算（M-02）
- 报表层口径混入单笔测算结果区（M-03）
- 性能改动未证明数值逐位一致（N-04）
- 结构性改动（规则解析、求根策略、计息基准）未经口径裁定直接动代码

## 缺陷与安全

- 缺陷走 [issue 模板](.github/ISSUE_TEMPLATE/bug_report.md)
- 安全漏洞走 [SECURITY.md](SECURITY.md) 的私密渠道，勿公开披露
