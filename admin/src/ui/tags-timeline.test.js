import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import StatusTag from './StatusTag.vue'
import Timeline from './Timeline.vue'

describe('StatusTag（原型 .tag 语义色）', () => {
  it('renders label with ok tone class', () => {
    const w = mount(StatusTag, { props: { status: 'ARCHIVED', label: '已归档' } })
    expect(w.text()).toContain('已归档')
    expect(w.find('.tag.tag-ok').exists()).toBe(true)
  })
  it('falls back to Chinese status label when no label', () => {
    const w = mount(StatusTag, { props: { status: 'PROCESSING' } })
    expect(w.text()).toContain('进行中')
    expect(w.find('.tag.tag-amber').exists()).toBe(true)
  })
  it('maps Chinese prototype states directly', () => {
    const w = mount(StatusTag, { props: { status: '待复核' } })
    expect(w.find('.tag.tag-amber').exists()).toBe(true)
  })
})

describe('Timeline（原型 tlItem 对齐）', () => {
  it('renders actor/role/time/text per item', () => {
    const w = mount(Timeline, { props: { items: [
      { actor: '李振国', role: 'MAINT_TECH', time: '2026-11-13 09:00', text: '签到' }
    ] } })
    expect(w.text()).toContain('李振国')
    expect(w.text()).toContain('维保技术员')
    expect(w.text()).toContain('签到')
    expect(w.findAll('.tl-item').length).toBe(1)
  })
  it('supports prototype title + cls variants', () => {
    const w = mount(Timeline, { props: { items: [
      { title: '当前状态 · 待复核', time: '2026-11-13 09:30', cls: 'active' }
    ] } })
    expect(w.find('.tl-item.active').exists()).toBe(true)
    expect(w.text()).toContain('当前状态 · 待复核')
  })
})