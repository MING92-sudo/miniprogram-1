import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusTag from './StatusTag.vue'
import Timeline from './Timeline.vue'

describe('StatusTag', () => {
  it('renders label and applies status color', () => {
    const w = mount(StatusTag, { props: { status: 'ARCHIVED', label: '已归档' } })
    expect(w.text()).toContain('已归档')
    expect(w.attributes('style')).toContain('var(--ok)')
  })
  it('falls back to Chinese status label when no label', () => {
    const w = mount(StatusTag, { props: { status: 'PROCESSING' } })
    expect(w.text()).toContain('进行中')
  })
})

describe('Timeline', () => {
  it('renders actor/role/time/text per item', () => {
    const w = mount(Timeline, { props: { items: [
      { actor: '李振国', role: 'MAINT_TECH', time: '2026-11-13 09:00', text: '签到' }
    ] } })
    expect(w.text()).toContain('李振国')
    expect(w.text()).toContain('维保技术员')
    expect(w.text()).toContain('签到')
    expect(w.findAll('.tl-item').length).toBe(1)
  })
})