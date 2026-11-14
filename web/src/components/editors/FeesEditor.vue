<script setup lang="ts">
import { Button, Checkbox, Input, Select, Typography } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import type { FeeDraft, FeeOwnerDraft } from './types'

/**
 * 费用编辑器（Task 2 从 CalculationForm 抽出）：逐笔费用（类型/金额/收取方式）。
 * 状态由父组件经 v-model 持有。
 */
const fees = defineModel<FeeDraft[]>({ required: true })

const { t } = useI18n()

/** ChargeMode 枚举（后端 FeeDto.chargeMode 注册值），标签走 i18n。 */
const CHARGE_MODE_VALUES = ['UPFRONT', 'DEDUCT_FROM_PRINCIPAL', 'AMORTIZED'] as const

/** 承担方枚举（后端 FeeDto.owner 注册值，ticket 04）：缺省承租人承担 = v1 姿态。 */
const FEE_OWNER_VALUES: FeeOwnerDraft[] = ['LESSEE', 'LESSOR']

const chargeModeOptions = computed(() =>
  CHARGE_MODE_VALUES.map((value) => ({ value, label: t(`enums.chargeMode.${value}`) })),
)
const feeOwnerOptions = computed(() =>
  FEE_OWNER_VALUES.map((value) => ({ value, label: t(`enums.feeOwner.${value}`) })),
)

function addFee(): void {
  fees.value.push({ type: '', amount: '', chargeMode: 'UPFRONT', owner: 'LESSEE',
    includeInIrr: false })
}

function removeFee(index: number): void {
  fees.value.splice(index, 1)
}
</script>

<template>
  <div>
    <Typography.Text type="secondary">
      {{ t('form.feesOther.fees.title') }}
    </Typography.Text>
    <div style="margin-top: 8px">
      <div
        v-for="(fee, idx) in fees"
        :key="idx"
        style="display: flex; gap: 8px; margin-bottom: 8px"
        data-test="fee-row"
      >
        <Input
          v-model:value="fee.type"
          :placeholder="t('form.feesOther.fees.typePh')"
          style="width: 160px"
        />
        <Input
          v-model:value="fee.amount"
          :placeholder="t('form.feesOther.fees.amountPh')"
          style="width: 140px"
        />
        <Select
          v-model:value="fee.chargeMode"
          :options="chargeModeOptions"
          :popup-match-select-width="false"
          style="width: 200px"
        />
        <Select
          v-model:value="fee.owner"
          :options="feeOwnerOptions"
          :popup-match-select-width="false"
          style="width: 140px"
          data-test="fee-owner"
        />
        <Checkbox v-model:checked="fee.includeInIrr" data-test="fee-include-irr">
          {{ t('form.feesOther.fees.includeIrr') }}
        </Checkbox>
        <Button danger @click="removeFee(idx)">
          {{ t('form.feesOther.fees.remove') }}
        </Button>
      </div>
    </div>
    <Button type="dashed" data-test="fee-add" @click="addFee()">
      {{ t('form.feesOther.fees.add') }}
    </Button>
  </div>
</template>
