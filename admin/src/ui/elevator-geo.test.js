import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { readFileSync } from 'node:fs'

vi.mock('../api/archive', () => ({
  updateElevator: vi.fn()
}))

import { updateElevator } from '../api/archive'
import ElevatorGeoModal from './ElevatorGeoModal.vue'

const ROW = {
  id: 'el_1',
  elevatorCode: 'EM-1',
  projectName: '测试小区',
  location: '重庆市渝中区测试路 1 号',
  lng: '106.5523',
  lat: '29.5628',
  geoStatus: 'OK'
}

async function mountModal(row = ROW) {
  const w = mount(ElevatorGeoModal, { props: { open: true, row } })
  await flushPromises()
  return w
}

async function submit(w) {
  await w.find('.modal-foot .btn-primary').trigger('click')
  await flushPromises()
}

describe('电梯定位编辑', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    updateElevator.mockReset()
    updateElevator.mockImplementation((id, body) => Promise.resolve({
      ...ROW,
      ...body,
      geoStatus: body.lng ? 'OK' : 'MISSING'
    }))
  })

  it('打开时回填详细地址和经纬度', async () => {
    const w = await mountModal()
    expect(w.find('input[name="geo-location"]').element.value).toBe(ROW.location)
    expect(w.find('input[name="geo-lng"]').element.value).toBe(ROW.lng)
    expect(w.find('input[name="geo-lat"]').element.value).toBe(ROW.lat)
    expect(w.text()).toContain('GCJ-02')
    expect(w.text()).toContain('签到围栏')
  })

  it('拒绝单边坐标且不调用更新接口', async () => {
    const w = await mountModal()
    await w.find('input[name="geo-lat"]').setValue('')
    await submit(w)
    expect(w.text()).toContain('经纬度须同时填写或同时留空')
    expect(updateElevator).not.toHaveBeenCalled()
    expect(w.emitted('saved')).toBeFalsy()
  })

  it('拒绝非数字坐标且不调用更新接口', async () => {
    const w = await mountModal()
    await w.find('input[name="geo-lng"]').setValue('abc')
    await submit(w)
    expect(w.text()).toContain('经度必须是有效数字')
    expect(updateElevator).not.toHaveBeenCalled()
  })

  it('拒绝越界坐标且不调用更新接口', async () => {
    const w = await mountModal()
    await w.find('input[name="geo-lng"]').setValue('180.000001')
    await submit(w)
    expect(w.text()).toContain('经度须在 -180 至 180 之间')
    expect(updateElevator).not.toHaveBeenCalled()
  })

  it('合法定位只提交地址与经纬度并返回更新行', async () => {
    const w = await mountModal()
    await w.find('input[name="geo-location"]').setValue('  重庆市渝中区新地址 2 号  ')
    await w.find('input[name="geo-lng"]').setValue(' 106.553 ')
    await w.find('input[name="geo-lat"]').setValue(' 29.563 ')
    await submit(w)
    expect(updateElevator).toHaveBeenCalledWith('el_1', {
      location: '重庆市渝中区新地址 2 号',
      lng: '106.553',
      lat: '29.563'
    })
    expect(w.emitted('saved')[0][0]).toMatchObject({
      id: 'el_1', location: '重庆市渝中区新地址 2 号', lng: '106.553', lat: '29.563', geoStatus: 'OK'
    })
    expect(w.emitted('close')).toBeTruthy()
  })

  it('经纬度同时留空可清除定位并触发重新采集', async () => {
    const w = await mountModal()
    await w.find('input[name="geo-lng"]').setValue('')
    await w.find('input[name="geo-lat"]').setValue('')
    await submit(w)
    expect(updateElevator).toHaveBeenCalledWith('el_1', {
      location: ROW.location,
      lng: '',
      lat: ''
    })
    expect(w.emitted('saved')[0][0].geoStatus).toBe('MISSING')
  })
})

describe('电梯定位入口接线', () => {
  it('台账行、档案抽屉和电梯档案页均复用同一定位弹窗并沿用编辑权限', () => {
    const list = readFileSync('src/views/ElevatorsView.vue', 'utf8')
    const detail = readFileSync('src/views/ElevatorDetailView.vue', 'utf8')
    for (const source of [list, detail]) {
      expect(source).toContain("import ElevatorGeoModal from '../ui/ElevatorGeoModal.vue'")
      expect(source).toContain('<ElevatorGeoModal')
      expect(source).toContain('code="elevator:edit"')
      expect(source).toContain('label="修改定位"')
    }
    expect(list).toContain('label="定位"')
    expect(list).toContain('经度')
    expect(list).toContain('纬度')
    expect(detail).toContain('经度')
    expect(detail).toContain('纬度')
  })
})
