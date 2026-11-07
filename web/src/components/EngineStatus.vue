<script setup lang="ts">
import { Alert, Card, List, Space, Tag, Typography } from 'ant-design-vue'
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { getAlgorithms, ping, ApiError } from '@/api/client'
import type { AlgorithmInfo } from '@/api/types'

const { t } = useI18n()

const pingStatus = ref<PingState>({ state: 'loading' })
const algorithms = ref<AlgorithmListState>({ state: 'loading' })

interface PingState {
  state: 'loading' | 'ready' | 'error'
  status?: string
  engine?: string
  error?: ApiError
}

interface AlgorithmListState {
  state: 'loading' | 'ready' | 'error'
  items?: AlgorithmInfo[]
  error?: ApiError
}

onMounted(async () => {
  ping().then(
    (res) => {
      pingStatus.value = { state: 'ready', status: res.status, engine: res.engine }
    },
    (err: unknown) => {
      pingStatus.value = {
        state: 'error',
        error: err instanceof ApiError ? err : new ApiError('UNKNOWN_ERROR', String(err), 0),
      }
    },
  )

  getAlgorithms().then(
    (items) => {
      algorithms.value = { state: 'ready', items }
    },
    (err: unknown) => {
      algorithms.value = {
        state: 'error',
        error: err instanceof ApiError ? err : new ApiError('UNKNOWN_ERROR', String(err), 0),
      }
    },
  )
})
</script>

<template>
  <Card :title="t('engine.cardTitle')" data-test="engine-status-card">
    <template #extra>
      <Tag v-if="pingStatus.state === 'ready'" color="green" data-test="engine-up">
        {{ pingStatus.status }} · engine {{ pingStatus.engine }}
      </Tag>
      <Tag v-else-if="pingStatus.state === 'loading'" color="blue">
        {{ t('engine.checking') }}
      </Tag>
      <Tag v-else color="red" data-test="engine-down">{{ t('engine.backendDown') }}</Tag>
    </template>

    <Alert
      v-if="algorithms.state === 'error'"
      type="error"
      show-icon
      :message="t('engine.algorithmsFailed', { code: algorithms.error?.code ?? '' })"
      :description="algorithms.error?.message"
    />

    <Space v-else direction="vertical" style="width: 100%">
      <List
        :data-source="algorithms.items ?? []"
        :loading="algorithms.state === 'loading'"
        size="small"
        row-key="code"
      >
        <template #renderItem="{ item }">
          <List.Item>
            <Typography.Text code>{{ item.code }}</Typography.Text>
            <Typography.Text type="secondary">{{ item.description }}</Typography.Text>
          </List.Item>
        </template>
      </List>
    </Space>
  </Card>
</template>
