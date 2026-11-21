<script setup lang="ts">
/**
 * 三段 EQ + 前级增益 + 一键旁路。
 *
 * 只对**受控来源**开放（服务端 capabilities.eq）。不可用时整个面板置灰并说明原因，
 * 而不是显示一组"能动但没用"的滑杆——那是最容易被当成"音频处理已实现"的假象。
 *
 * 不出现 Bit-Perfect / DAC 独占 / 升频：浏览器链路无法承诺，写了就是误导。
 */
import { computed } from 'vue'
import type { EqSettings } from '@/audio/equalizer'

const props = defineProps<{
  settings: EqSettings & { enabled: boolean }
  /** 当前曲目是否允许调音 */
  available: boolean
  /** 环境是否支持 Web Audio */
  supported: boolean
}>()

const emit = defineEmits<{
  (e: 'change', patch: Partial<EqSettings & { enabled: boolean }>): void
  (e: 'bypass'): void
  (e: 'reset'): void
}>()

const disabled = computed(() => !props.supported || !props.available)

const reason = computed(() => {
  if (!props.supported) {
    return '当前浏览器不支持 Web Audio，无法调音。'
  }
  if (!props.available) {
    return '当前音源未确认为可处理来源，调音不可用。'
  }
  return ''
})

const bands = [
  { key: 'low' as const, label: '低频', hint: '200 Hz 搁架' },
  { key: 'mid' as const, label: '中频', hint: '1 kHz 峰值' },
  { key: 'high' as const, label: '高频', hint: '4 kHz 搁架' },
]

function onBand(key: 'low' | 'mid' | 'high', event: Event): void {
  const value = Number((event.target as HTMLInputElement).value)
  emit('change', { [key]: value } as Partial<EqSettings>)
}

function onPreamp(event: Event): void {
  emit('change', { preamp: Number((event.target as HTMLInputElement).value) })
}

function formatDb(value: number): string {
  const rounded = Math.round(value * 10) / 10
  return `${rounded > 0 ? '+' : ''}${rounded.toFixed(1)} dB`
}
</script>

<template>
  <div class="eq" role="group" aria-label="调音">
    <header class="eq__head">
      <span class="eq__title">调音</span>
      <button
        type="button"
        class="eq__bypass"
        :class="{ 'is-on': settings.enabled }"
        :disabled="disabled"
        :aria-pressed="settings.enabled"
        @click="emit('bypass')"
      >
        {{ settings.enabled ? '已启用 · 点击旁路' : '旁路中 · 点击启用' }}
      </button>
    </header>

    <p v-if="reason" class="eq__reason">{{ reason }}</p>

    <div class="eq__bands" :class="{ 'is-disabled': disabled }">
      <label v-for="band in bands" :key="band.key" class="eq__band">
        <span class="eq__label">
          {{ band.label }}
          <span class="eq__value">{{ formatDb(settings[band.key]) }}</span>
        </span>
        <input
          type="range"
          min="-12"
          max="12"
          step="0.5"
          :value="settings[band.key]"
          :disabled="disabled || !settings.enabled"
          :aria-label="`${band.label}增益`"
          @input="onBand(band.key, $event)"
        />
        <span class="eq__hint">{{ band.hint }}</span>
      </label>

      <label class="eq__band">
        <span class="eq__label">
          前级
          <span class="eq__value">{{ formatDb(settings.preamp) }}</span>
        </span>
        <input
          type="range"
          min="-12"
          max="6"
          step="0.5"
          :value="settings.preamp"
          :disabled="disabled || !settings.enabled"
          aria-label="前级增益"
          @input="onPreamp"
        />
        <span class="eq__hint">降低可防削波</span>
      </label>
    </div>

    <footer class="eq__foot">
      <button
        type="button"
        class="eq__reset"
        :disabled="disabled"
        @click="emit('reset')"
      >
        恢复平直
      </button>
      <span class="eq__note">仅本机实时处理，不改动文件</span>
    </footer>
  </div>
</template>

<style scoped>
.eq {
  width: 268px;
  padding: var(--s-3);
  display: grid;
  gap: var(--s-3);
}

.eq__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
}

.eq__title {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text);
}

.eq__bypass {
  height: 24px;
  padding: 0 var(--s-2);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-line);
  font-size: 10.5px;
  color: var(--c-text-2);
  transition: color var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}

.eq__bypass.is-on {
  border-color: var(--c-accent-line);
  color: var(--c-accent);
  background: var(--c-accent-weak-2);
}

.eq__bypass:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.eq__reason {
  margin: 0;
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--c-text-3);
}

.eq__bands {
  display: grid;
  gap: var(--s-2);
}

.eq__bands.is-disabled {
  opacity: 0.45;
}

.eq__band {
  display: grid;
  gap: 2px;
}

.eq__label {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.eq__value {
  font-family: var(--font-mono);
  font-size: 10.5px;
  color: var(--c-text-3);
}

.eq__hint {
  font-size: 10px;
  color: var(--c-text-3);
}

input[type='range'] {
  width: 100%;
  accent-color: var(--c-accent);
}

.eq__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-2);
  border-top: 1px solid var(--c-line-soft);
  padding-top: var(--s-2);
}

.eq__reset {
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}

.eq__reset:hover:not(:disabled) {
  color: var(--c-text);
}

.eq__note {
  font-size: 10px;
  color: var(--c-text-3);
}
</style>
