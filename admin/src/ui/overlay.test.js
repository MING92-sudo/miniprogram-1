import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Modal from './Modal.vue'
import Drawer from './Drawer.vue'

describe('Modal（原型 openModal 对齐）', () => {
  it('danger variant has signal border class', () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true } })
    expect(w.find('.modal.danger').exists()).toBe(true)
    expect(w.find('.scrim').exists()).toBe(true)
  })
  it('requireConfirm disables confirm until checkbox checked', async () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true, requireConfirm: true } })
    const btn = w.find('.modal-foot .btn-solid-sig')
    expect(btn.attributes('disabled')).toBeDefined()
    await w.find('.modal-body .chk').setValue(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(w.emitted('confirm')).toBeTruthy()
  })
  it('renders icon head and sub title', () => {
    const w = mount(Modal, { props: { open: true, title: '设备码', sub: 'DT-CB-0318', icon: 'qr' } })
    expect(w.find('.mh-ico use').attributes('href')).toBe('#i-qr')
    expect(w.find('.mh-sub').text()).toBe('DT-CB-0318')
  })
})

describe('Drawer（原型 openDrawer 对齐）', () => {
  it('renders meta bar and emits close on Esc', async () => {
    const w = mount(Drawer, { props: { open: true, title: '详情', meta: { role: 'MAINT_MGR', perm: 'elevator:view' } }, attachTo: document.body })
    expect(w.text()).toContain('MAINT_MGR')
    expect(w.find('.drawer-meta').exists()).toBe(true)
    await w.find('.drawer').trigger('keydown.esc')
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
  it('renders 620px prototype drawer with head sub and foot note', () => {
    const w = mount(Drawer, { props: { open: true, title: 'YJ-2026-0117', sub: '云栖里 · 3 台', note: 'handover:view' } })
    expect(w.find('.dh-sub').text()).toBe('云栖里 · 3 台')
    expect(w.find('.ff-note').text()).toBe('handover:view')
  })
})