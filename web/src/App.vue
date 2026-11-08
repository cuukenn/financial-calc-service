<script setup lang="ts">
import { ConfigProvider, Layout, TabPane, Tabs, Typography, theme } from 'ant-design-vue'
import enUS from 'ant-design-vue/es/locale/en_US'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import CalculationForm from '@/components/CalculationForm.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import MortgageForm from '@/components/MortgageForm.vue'

// R82: 最小 v1 单页形态（无 Router / Pinia）；R85: 语言切换器入页头，
// AntD 组件库文案随全局 locale 响应式切换（zh_CN ↔ en_US import swap）。
const { t, locale } = useI18n()
const antdLocale = computed(() => (locale.value === 'en-US' ? enUS : zhCN))

/**
 * R100：两个测算场景共用同一个引擎与同一个 POST /api/v1/calculations 契约，
 * 所以 Tabs 只是同一页内的场景切换（无 Router / Pinia / 持久化，选中态不记忆 —— Task 6）。
 * 面板内容各自拥有自己的表单组件；结果区两个场景共用未改动的 ResultPanel。
 *
 * 非活动面板只隐藏、不销毁：切走的场景仍在 DOM 里但被 AntD 打上 inline
 * display:none 与 aria-hidden="true"（未访问过的面板才缺席 —— Tabs 懒渲染），
 * 所以「隐藏另一套要素」依然是真实的显示语义，而半填的表单不会被一次 peek
 * 另一个场景静默清空。显式的重置行为归 Task 6，这里不做隐式销毁。
 * 测试据此断言 TabPane 的 aria-hidden / inline display（见 MortgageForm.spec 的 paneState），
 * 而不是 jsdom 里跨显示/隐藏周期会留下过期 computed style 的 isVisible()。
 */
type ScenarioKey = 'lease' | 'mortgage'
const scenario = ref<ScenarioKey>('lease')

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
        <Tabs v-model:activeKey="scenario" data-test="scenario-tabs">
          <TabPane key="lease" :tab="t('app.scenario.lease')">
            <div data-test="scenario-lease">
              <CalculationForm />
            </div>
          </TabPane>
          <TabPane key="mortgage" :tab="t('app.scenario.mortgage')">
            <div data-test="scenario-mortgage">
              <MortgageForm />
            </div>
          </TabPane>
        </Tabs>
      </Content>
      <Footer style="text-align: center">{{ t('app.footer') }}</Footer>
    </Layout>
  </ConfigProvider>
</template>
