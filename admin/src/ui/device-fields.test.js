import { describe, it, expect } from 'vitest'
import {
  DEVICE_FIELDS, REQUIRED_DEVICE_FIELDS, REQUIRED_DEVICE_LABELS,
  deviceRules, deviceFormOf, missingRequiredOf
} from './device-fields'

// 用户 2026-11-19：设备信息档案 8 项字段，前 5 项是上传维保记录的必传项；
// 「标签不允许错误」——电梯编码≠设备代码、注册代码≠产品编号，这里逐字锁定。
describe('设备信息档案字段口径', () => {
  it('8 项字段与标签（顺序固定）', () => {
    expect(DEVICE_FIELDS.map((f) => f.key)).toEqual([
      'elevatorCode', 'deviceCode', 'registrationCode', 'insideNumber', 'factoryNumber',
      'projectName', 'regionId', 'nextCheckDate'
    ])
    expect(DEVICE_FIELDS.map((f) => f.label)).toEqual([
      '电梯编码', '设备代码', '注册代码', '单位内编号', '出厂编号',
      '项目名称（小区名称）', '所在区域', '年检日期'
    ])
  })

  it('前 5 项必传，后 3 项选填', () => {
    expect(REQUIRED_DEVICE_LABELS).toEqual(['电梯编码', '设备代码', '注册代码', '单位内编号', '出厂编号'])
    expect(REQUIRED_DEVICE_FIELDS).toHaveLength(5)
    expect(DEVICE_FIELDS.slice(5).every((f) => !f.required)).toBe(true)
  })

  it('校验文案带字段标签，不只说「必填」', () => {
    const rules = deviceRules()
    expect(Object.keys(rules).sort()).toEqual(
      ['deviceCode', 'elevatorCode', 'factoryNumber', 'insideNumber', 'registrationCode'].sort()
    )
    expect(rules.registrationCode[0]('')).toBe('注册代码必填')
    expect(rules.registrationCode[0]('  ')).toBe('注册代码必填')
    expect(rules.registrationCode[0]('TSCQ5001120001')).toBe('')
    expect(rules.insideNumber[0]('')).toBe('单位内编号必填')
    // 选填项没有规则
    expect(rules.projectName).toBeUndefined()
    expect(rules.regionId).toBeUndefined()
    expect(rules.nextCheckDate).toBeUndefined()
  })

  it('注册代码取 registrationCode，兼容旧 regCode 与档案 profile', () => {
    expect(deviceFormOf({ registrationCode: 'REG-1' }, null).registrationCode).toBe('REG-1')
    expect(deviceFormOf({ regCode: 'REG-2' }, null).registrationCode).toBe('REG-2')
    expect(deviceFormOf({}, { platform: { registrationCode: 'REG-3' } }).registrationCode).toBe('REG-3')
    // 设备代码与电梯编码不得互相顶替
    const f = deviceFormOf({ elevatorCode: 'EM-1', deviceCode: 'DT-1' }, null)
    expect(f.elevatorCode).toBe('EM-1')
    expect(f.deviceCode).toBe('DT-1')
  })

  it('缺失项优先用后端下发的 missingRequired，前端兜底算法同口径', () => {
    expect(missingRequiredOf({ missingRequired: ['设备代码'] })).toEqual(['设备代码'])
    expect(missingRequiredOf({ elevatorCode: 'EM-1' })).toEqual(
      ['设备代码', '注册代码', '单位内编号', '出厂编号']
    )
    expect(missingRequiredOf({
      elevatorCode: 'EM-1', deviceCode: 'DT-1', registrationCode: 'R-1',
      insideNumber: 'KT-1', factoryNumber: 'F-1'
    })).toEqual([])
    // 旧接口只回 regCode 时也不误判
    expect(missingRequiredOf({
      elevatorCode: 'EM-1', deviceCode: 'DT-1', regCode: 'R-1',
      insideNumber: 'KT-1', factoryNumber: 'F-1'
    })).toEqual([])
  })
})
