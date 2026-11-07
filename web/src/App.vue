<script setup lang="ts">
import { ConfigProvider, Layout, Space, Typography, theme } from 'ant-design-vue'
import enUS from 'ant-design-vue/es/locale/en_US'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import CalculationForm from '@/components/CalculationForm.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'

// R82: 最小 v1 单页形态（无 Router / Pinia）；R85: 语言切换器入页头，
// AntD 组件库文案随全局 locale 响应式切换（zh_CN ↔ en_US import swap）。
const { t, locale } = useI18n()
const antdLocale = computed(() => (locale.value === 'en-US' ? enUS : zhCN))

const { Title } = Typography
const { Header, Content, Footer } = Layout
</script>

<template>
  <ConfigProvider :locale="antdLocale" :theme="{ algorithm: theme.defaultAlgorithm }">
    <Layout style="min-height: 100vh">
      <Header
        style="background: #fff; border-bottom: 1px solid #f0f0f0; display: flex; align-items: center; justify-content: space-between"
      >
        <Title :level="3" style="line-height: 64px; margin: 0">{{ t('app.title') }}</Title>
        <LanguageSwitcher />
      </Header>
      <Content style="padding: 24px">
        <Space direction="vertical" size="large" style="width: 100%">
          <CalculationForm />
        </Space>
      </Content>
      <Footer style="text-align: center">{{ t('app.footer') }}</Footer>
    </Layout>
  </ConfigProvider>
</template>
