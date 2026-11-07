/**
 * 规则行 ruleId 派生（Task 3 修正：新增行不再与既有 ID 重复）。
 *
 * 扫描已有 `R<数字>` 形式 ID 的最大序号，返回其后继；非 R 格式或空列表从 R1 起。
 * 删除中间行后再次添加不会复用/撞号（如已有 R1/R3 → 新增为 R4）。
 */
export function nextRuleId(existing: string[]): string {
  let max = 0
  for (const id of existing) {
    const m = /^R(\d+)$/i.exec(id.trim())
    if (m) max = Math.max(max, Number(m[1]))
  }
  return `R${max + 1}`
}
