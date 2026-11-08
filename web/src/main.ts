import { createApp } from 'vue'

import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
// Task 6 §4：共用工作台样式（大块卡片 / 派生读数的间距对齐 + 阈值仪表配色）
// 只在这里定义一次，必须在 AntD 的 reset 之后导入。
import './styles/workbench.css'

import App from './App.vue'
import i18n from './i18n'

createApp(App).use(Antd).use(i18n).mount('#app')
