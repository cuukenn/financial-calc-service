<script setup lang="ts">
import { Input, Typography } from 'ant-design-vue'
import { useI18n } from 'vue-i18n'

/**
 * 显式租金编辑器（Task 2 从 CalculationForm 抽出）：EXPLICIT_RENT 方式的逐期租金。
 * 长度始终与期数对齐（对齐逻辑在父组件 watch 中）；状态由父组件经 v-model 持有。
 */
const rents = defineModel<string[]>({ required: true })

defineProps<{ periods: number }>()

const { t } = useI18n()
</script>

<template>
  <div data-test="rents-editor" style="margin-top: 12px">
    <Typography.Text type="secondary">
      {{ t('form.rentStructure.explicitRents.label', { periods: periods ?? 0 }) }}
    </Typography.Text>
    <div style="display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px">
      <Input
        v-for="(_, idx) in rents"
        :key="idx"
        v-model:value="rents[idx]"
        :placeholder="t('form.rentStructure.explicitRents.periodPh', { n: idx + 1 })"
        style="width: 120px"
        data-test="rent-input"
      />
    </div>
  </div>
</template>
