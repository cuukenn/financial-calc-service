<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

/**
 * ECharts 薄封装（Task 3 / R82：直接用 echarts，不引 vue-echarts 包装库）。
 * 仅在有 option 时初始化渲染，window resize 时自适应，卸载时释放实例。
 */
const props = defineProps<{ option: echarts.EChartsOption }>()

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
