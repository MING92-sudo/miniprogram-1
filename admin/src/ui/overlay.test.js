import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Modal from './Modal.vue'
import Drawer from './Drawer.vue'

describe('Modal', () => {
  it('danger variant has signal class', () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true } })
    expect(w.find('.modal--danger').exists()).toBe(true)
  })
  it('requireConfirm disables confirm until checkbox checked', async () => {
    const w = mount(Modal, { props: { open: true, title: '报废', danger: true, requireConfirm: true } })
    const btn = w.find('.modal-confirm')
    expect(btn.attributes('disabled')).toBeDefined()
    await w.find('.modal-check').setValue(true)
    expect(btn.attributes('disabled')).toBeUndefined()
    await btn.trigger('click')
    expect(w.emitted('confirm')).toBeTruthy()
  })
})

describe('Drawer', () => {
  it('renders meta bar and emits close on Esc', async () => {
    const w = mount(Drawer, { props: { open: true, title: '详情', meta: { role: 'MAINT_MGR', perm: 'elevator:view' } }, attachTo: document.body })
    expect(w.text()).toContain('MAINT_MGR')
    await w.find('.drawer').trigger('keydown.esc')
    expect(w.emitted('close')).toBeTruthy()
    w.unmount()
  })
})