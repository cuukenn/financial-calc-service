<script setup lang="ts">
import { GlobalOutlined } from '@ant-design/icons-vue'
import { Button, Dropdown } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { setLocale, type AppLocale } from '@/i18n'

/**
 * 语言切换器（R85）：页头 Dropdown，切换全局 locale 并持久化到 localStorage。
 * 全部 UI 文案与 AntD ConfigProvider locale 均随其响应式更新。
 */
const { locale } = useI18n()

interface MenuInfo {
  key: string
}

const items = computed(() => [
  { key: 'zh-CN', label: '简体中文' },
  { key: 'en-US', label: 'English' },
])

function onMenuClick(info: MenuInfo): void {
  setLocale(info.key as AppLocale)
}
</script>

<template>
  <Dropdown
    :trigger="['click']"
    :menu="{ items }"
    data-test="language-switcher"
    @click="onMenuClick"
  >
    <Button size="small" data-test="language-switcher-btn">
      <template #icon>
        <GlobalOutlined />
      </template>
      🌐 {{ locale === 'en-US' ? 'English' : '中文' }}
    </Button>
  </Dropdown>
</template>
