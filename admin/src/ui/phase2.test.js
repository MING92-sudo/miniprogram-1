import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import { M } from './prototype-mock'
import SyncView from '../views/SyncView.vue'
import MsgTemplatesView from '../views/MsgTemplatesView.vue'
import ReviewsView from '../views/ReviewsView.vue'
import BoardView from '../views/BoardView.vue'

const router = createRouter({ history: createWebHashHistory(), routes: [
  { path: '/', component: { template: '<div/>' } },
  { path: '/sync', component: { template: '<div/>' } },
  { path: '/msg-templates', component: { template: '<div/>' } },
  { path: '/reviews', component: { template: '<div/>' } },
  { path: '/board', component: { template: '<div/>' } },
  { path: '/dashboard', component: { template: '<div/>' } }
] })
function mountView(V) { return mount(V, { global: { plugins: [router] } }) }

describe('prototype-mock 基准集（原型 §9 不变量）', () => {
  it('12 条自检全部通过', () => {
    const checks = M.selfCheck()
    expect(checks.length).toBe(12)
    expect(checks.every(c => c.pass)).toBe(true)
  })
  it('台量闭合 20 台，范围解析 SUP14/INST6/CLIENT14', () => {
    expect(M.ELEVATORS.length).toBe(20)
    expect(M.scopeElevators('MAINT_SUP').length).toBe(14)
    expect(M.scopeElevators('INST_MGR').length).toBe(6)
    expect(M.scopeElevators('CLIENT_ADMIN').length).toBe(14)
  })
})

describe('二期 4 页（原型对齐 · 演示数据）', () => {
  beforeEach(() => { setActivePinia(createPinia()); router.push('/') })
  it('SyncView renders table + field map + code maps, no bulk retry entry', async () => {
    const w = mountView(SyncView)
    await flushPromises()
    expect(w.text()).toContain('监管平台同步')
    expect(w.text()).toContain('字段映射')
    expect(w.text()).toContain('维保类别码表')
    expect(w.text()).toContain('严重事故隐患码表')
    // 合规：AGENTS §2.4 禁批量重试入口
    expect(w.text()).not.toContain('批量重报')
  })
  it('SyncView per-row retry flips 失败 → 已重试（演示）', async () => {
    const w = mountView(SyncView)
    await flushPromises()
    const before = w.text()
    expect(before).toContain('失败')
    const retryBtn = w.findAll('button').find(b => b.text() === '重报')
    expect(retryBtn).toBeTruthy()
    await retryBtn.trigger('click')
    await flushPromises()
    expect(w.text()).toContain('已重试')
  })
  it('MsgTemplatesView renders templates and preview renders variables', async () => {
    const w = mountView(MsgTemplatesView)
    await flushPromises()
    expect(w.text()).toContain('消息模板')
    expect(w.text()).toContain('使用单位签字提醒')
    const previewBtn = w.findAll('button').find(b => b.text() === '预览')
    await previewBtn.trigger('click')
    await flushPromises()
    expect(w.find('.msg-preview').text()).toContain('启元物业')
  })
  it('ReviewsView renders stat cards + distribution + table', async () => {
    const w = mountView(ReviewsView)
    await flushPromises()
    expect(w.text()).toContain('服务评价')
    expect(w.text()).toContain('平均分')
    expect(w.findAll('.dist-row').length).toBe(5)
    expect(w.findAll('.stars').length).toBeGreaterThan(0)
  })
  it('BoardView renders full-screen board with 4 KPIs and clock', async () => {
    const w = mountView(BoardView)
    await flushPromises()
    expect(w.find('.board').exists()).toBe(true)
    expect(w.findAll('.bkpi').length).toBe(4)
    expect(w.find('.board-clock').text()).toContain('2026-11-13')
    expect(w.text()).toContain('待接单池')
    expect(w.text()).toContain('项目调度态势')
    expect(w.text()).toContain('班组在线')
    w.unmount()
  })
})