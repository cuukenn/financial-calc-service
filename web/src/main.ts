import { createApp } from 'vue'

import 'ant-design-vue/dist/reset.css'
// Task 6 §4：共用工作台样式（大块卡片 / 派生读数的间距对齐 + 阈值仪表配色）
// 只在这里定义一次，必须在 AntD 的 reset 之后导入。
import './styles/workbench.css'

import App from './App.vue'
import i18n from './i18n'

// U1（2026-11-11 scan）：不再 `app.use(Antd)` 整包注册——全部 20 个 SFC 逐文件按需
// 导入自己用到的组件（audit 过：无全局注册依赖、无动态组件、无 message/Modal API），
// reset.css 仍需全局引入一次。新增 AntD 组件时在用它的 SFC 里 import 即可。
createApp(App).use(i18n).mount('#app')
