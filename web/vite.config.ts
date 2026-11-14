/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// R81: dev server proxies /api to the local calc-server so the backend needs no CORS.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // U1（2026-11-11 scan）：vendor 拆块——总量的减法已经由 echarts/core + 去掉
        // AntD 整包挂载做完（2839.9 → 1793.0 kB，−37%），这一步只做缓存的减法：
        // 库代码与业务代码分开哈希，业务迭代不再打掉整包缓存。echarts 因按需
        // register 已经小很多，与业务同块即可；antd 单独成块（它是剩余大头的来源，
        // 再往下要靠 unplugin 按需解析组件+图标，属下一轮）。
        manualChunks(id: string) {
          if (id.includes('node_modules')) {
            if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
            if (id.includes('ant-design-vue') || id.includes('@ant-design')
              || id.includes('dayjs')) return 'antd'
            return 'vendor'
          }
          return undefined
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Task 7 修复轮 1（R138）：AntD 全量挂载 + jsdom 在本机要 5–17s/用例（`npm run test`
    // 曾在两条用例上撞默认 5000ms 超时：CalculationForm 的 'shows the rule-rows editor as the
    // primary editor for RULE_DRIVEN' 与 MortgageForm 的 'shows 50.00% 月房贷支出/收入比 as
    // compliant (equal to the limit is not a breach)'）。上限显式钉在这里而不是逐用例放宽：30s 足够容纳
    // 满载机上的挂载成本，又不足以让一次真正的挂起（死循环 / 未 resolve 的 promise）悄悄通过。
    // 配套：`test-utils/forms.ts` 里那个 40ms 真实等待保留（它替 AntD FormItem 的
    // useDebounce(10ms) 与错误文案进出场过渡收尾），没有把输出噪声静音。
    testTimeout: 30000,
    hookTimeout: 30000,
  },
})
