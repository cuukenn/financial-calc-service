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
 * destroyInactiveTabPane：只渲染当前场景的面板 —— 切走的场景整体卸载，「隐藏另一套
 * 要素」是真实的 DOM 语义，而不是 display:none 的视觉伪装，测试也据此用 exists() 断言。
 * （AntD 默认把访问过的面板留在 DOM 里用 inline display:none 隐藏，jsdom 的
 * getComputedStyle 在跨显示/隐藏周期后仍会返回过期的 none，isVisible() 不可信。）
 * 代价：切换场景会丢弃另一个表单里已填的要素 —— v1 无持久化，Task 6 负责场景记忆。
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
        <Tabs v-model:activeKey="scenario" data-test="scenario-tabs" destroy-inactive-tab-pane>
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
