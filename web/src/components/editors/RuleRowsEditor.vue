<script setup lang="ts">
import { Button, Card, Input, InputNumber, Space, Typography } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { nextRuleId } from '../ruleIds'
import type { RuleRowDraft } from './types'

/**
 * 规则行编辑器（Task 2 从 CalculationForm 抽出）：分段利率规则行 + 预设按钮。
 * 对全部方式开放（RULE_DRIVEN 时为必填规则集）；行状态由父组件经 v-model 持有。
 */
const rows = defineModel<RuleRowDraft[]>({ required: true })

const props = defineProps<{ periods: number }>()

const { t } = useI18n()

function addRow(): void {
  const id = nextRuleId(rows.value.map((row) => row.ruleId))
  rows.value.push({
    ruleId: id,
    priority: (rows.value.length + 1) * 10,
    annualRate: '',
  })
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
}

/** 预设按钮文案由实际计算派生（Task 3 修正：不再硬编码 1-6/7-12）。 */
const presetLabel = computed(() => {
  const total = props.periods ?? 0
  const half = Math.floor(total / 2)
  return t('form.rentStructure.ruleRows.preset', {
    from: 1,
    to: half,
    from2: half + 1,
    to2: total,
  })
})

/** 预设：1 ~ 半程 @3%、半程+1 ~ 期末 @6%（期数 12 时即黄金分段 1-6 / 7-12）。 */
function presetSegmentedRates(): void {
  const total = props.periods ?? 0
  const half = Math.floor(total / 2)
  rows.value = [
    { ruleId: 'R1', priority: 10, fromPeriod: 1, toPeriod: half, annualRate: '0.03' },
    { ruleId: 'R2', priority: 20, fromPeriod: half + 1, toPeriod: total, annualRate: '0.06' },
  ]
}
</script>

<template>
  <Card
    type="inner"
    :title="t('form.rentStructure.ruleRows.title')"
    size="small"
    data-test="rule-rows-editor"
  >
    <Typography.Text type="secondary">
      {{ t('form.rentStructure.ruleRows.hint') }}
    </Typography.Text>
    <div style="margin-top: 8px">
      <div
        v-for="(row, idx) in rows"
        :key="idx"
        style="display: flex; gap: 8px; margin-bottom: 8px"
        data-test="rule-row"
      >
        <Input
          v-model:value="row.ruleId"
          :placeholder="t('form.rentStructure.ruleRows.ruleIdPh')"
          style="width: 90px"
        />
        <InputNumber
          v-model:value="row.priority"
          :placeholder="t('form.rentStructure.ruleRows.priorityPh')"
          style="width: 90px"
        />
        <InputNumber
          v-model:value="row.fromPeriod"
          :min="1"
          :placeholder="t('form.rentStructure.ruleRows.fromPeriodPh')"
          style="width: 100px"
        />
        <InputNumber
          v-model:value="row.toPeriod"
          :min="1"
          :placeholder="t('form.rentStructure.ruleRows.toPeriodPh')"
          style="width: 100px"
        />
        <Input
          v-model:value="row.annualRate"
          :placeholder="t('form.rentStructure.ruleRows.annualRatePh')"
          style="width: 150px"
          data-test="rule-annual-rate"
        />
        <Button danger @click="removeRow(idx)">
          {{ t('form.rentStructure.ruleRows.remove') }}
        </Button>
      </div>
    </div>
    <Space>
      <Button type="dashed" data-test="rule-add" @click="addRow()">
        {{ t('form.rentStructure.ruleRows.add') }}
      </Button>
      <Button data-test="rule-preset" @click="presetSegmentedRates()">
        {{ presetLabel }}
      </Button>
    </Space>
  </Card>
</template>
