<script setup lang="ts">
import { ConfigProvider, Layout, Space, Typography, theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

import EngineStatus from '@/components/EngineStatus.vue'

// R82: 最小 v1 单页形态（无 Router / Pinia）；Task 2/3 的表单与结果区块将挂在此处。
const { Title } = Typography
const { Header, Content, Footer } = Layout
</script>

<template>
  <ConfigProvider :locale="zhCN" :theme="{ algorithm: theme.defaultAlgorithm }">
    <Layout style="min-height: 100vh">
      <Header style="background: #fff; border-bottom: 1px solid #f0f0f0">
        <Title :level="3" style="line-height: 64px; margin: 0">租赁测算工作台</Title>
      </Header>
      <Content style="padding: 24px">
        <Space direction="vertical" size="large" style="width: 100%">
          <EngineStatus />
        </Space>
      </Content>
      <Footer style="text-align: center">financial-calc-service · 可视化测算前端（Plan 3）</Footer>
    </Layout>
  </ConfigProvider>
</template>
