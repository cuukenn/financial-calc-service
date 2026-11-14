<script setup lang="ts">
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

// U1（2026-11-11 scan）：echarts 按需注册——本仓库的三个图（本息构成 bar、余额 line、
// 累计现金流 line）只用到 bar/line + grid/legend/tooltip/dataZoom + canvas 渲染器，
// 不再整包 `import * as echarts from 'echarts'`。新增图表类型时在这里补 register，
// 这是唯一登记点。
echarts.use([
  BarChart,
  LineChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  DataZoomComponent,
  CanvasRenderer,
])

/**
 * ECharts 薄封装（Task 3 / R82：直接用 echarts，不引 vue-echarts 包装库）。
 * 仅在有 option 时初始化渲染，window resize 时自适应，卸载时释放实例。
 */
const props = defineProps<{ option: echarts.EChartsCoreOption }>()

const el = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

function render(): void {
  if (el.value) {
    chart ??= echarts.init(el.value)
    chart.setOption(props.option, true)
  }
}

function onResize(): void {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})

watch(
  () => props.option,
  () => render(),
  { deep: true },
)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" style="width: 100%; height: 320px" />
</template>
