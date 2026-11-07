<script setup lang="ts">
import { GlobalOutlined } from '@ant-design/icons-vue'
import { Button, Dropdown, Menu } from 'ant-design-vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import { setLocale, type AppLocale } from '@/i18n'

/**
 * 语言切换器（R85）：页头 Dropdown（overlay 挂 Menu），切换全局 locale 并持久化到
 * localStorage。全部 UI 文案与 AntD ConfigProvider locale 均随其响应式更新。
 *
 * antd-vue 4.2.6 的 Dropdown 只消费 overlay 插槽（renderOverlay 读 props.overlay/slots.overlay）
 * 且自身不发 click 事件（click 属于 Menu），故把 Menu 放进 #overlay 并监听 Menu 的 @click。
 */
const { locale } = useI18n()

interface MenuInfo {
  key: string | number
}

const items = computed(() => [
  { key: 'zh-CN', label: '简体中文' },
  { key: 'en-US', label: 'English' },
])

function onMenuClick(info: MenuInfo): void {
  setLocale(String(info.key) as AppLocale)
}
</script>

<template>
  <Dropdown :trigger="['click']" data-test="language-switcher">
    <Button size="small" data-test="language-switcher-btn">
      <template #icon>
        <GlobalOutlined />
      </template>
      🌐 {{ locale === 'en-US' ? 'English' : '中文' }}
    </Button>
    <template #overlay>
      <Menu :items="items" :selectable="false" data-test="language-menu" @click="onMenuClick" />
    </template>
  </Dropdown>
</template>
