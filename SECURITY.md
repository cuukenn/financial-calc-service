# 安全策略

## 支持版本

| 版本 | 支持 |
|---|---|
| 最新 tag 发布（见 [Releases](https://github.com/cuukenn/financial-calc-service/releases)） | ✅ |
| 更早版本 | ❌（请先升级） |

## 报告漏洞

**请勿在公开 issue 中披露安全漏洞。**

使用 GitHub 的私密披露渠道：仓库页 **Security → Advisories → Report a vulnerability**，
或通过维护者主页的联系方式私下反馈。

- 请包含：复现步骤、影响范围、环境信息
- 会在 7 天内确认，修复后将与你确认披露时间线

## 已知设计边界

- v1 无鉴权体系（R79 最小执行）：请勿将未加网关的实例直接暴露公网
- 请求体上限 1MB（`RequestBodySizeFilter`）
