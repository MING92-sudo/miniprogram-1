import { describe, it, expect } from 'vitest'
import {
  DEVICE_FIELDS, REQUIRED_DEVICE_FIELDS, REQUIRED_DEVICE_LABELS,
  deviceRules, deviceFormOf, missingRequiredOf, applyPlatformElevator
} from './device-fields'

describe('设备信息档案字段口径', () => {
  it('9 项字段与标签（顺序固定）', () => {
    expect(DEVICE_FIELDS.map((f) => f.key)).toEqual([
      'elevatorCode', 'deviceCode', 'registrationCode', 'insideNumber', 'factoryNumber',
      'certNo', 'projectName', 'regionId', 'nextCheckDate'
    ])
    expect(DEVICE_FIELDS.map((f) => f.label)).toEqual([
      '电梯编码', '设备代码', '注册代码', '单位内编号', '出厂编号',
      '登记证编码', '项目名称（小区名称）', '所在区域', '下次年检日期'
    ])
  })

  it('前 5 项必传，后 4 项选填', () => {
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
    expect(rules.certNo).toBeUndefined()
  })

  it('登记证编码为独立选填字段，取自 certNo，不回退注册代码', () => {
    expect(deviceFormOf({ certNo: '梯11渝BSP8194(20)' }, null).certNo).toBe('梯11渝BSP8194(20)')
    expect(deviceFormOf({ registrationCode: 'REG-1' }, null).certNo).toBe('')
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

  it('2.7 回填只覆盖平台有值的标识字段，不回填脱敏手机号', () => {
    const form = { elevatorCode: 'LOCAL-1', deviceCode: '', registrationCode: 'R-LOCAL', factoryNumber: '' }
    const filled = applyPlatformElevator(form, {
      elevatorCode: '130421', deviceCode: 'DT-1', registrationCode: '',
      factoryNumber: 'FN-1', elevatorAdministerPhone: '1582****892', emergencyPhone: '400****588'
    })

    expect(form).toEqual({
      elevatorCode: '130421', deviceCode: 'DT-1', registrationCode: 'R-LOCAL', factoryNumber: 'FN-1'
    })
    expect(filled).toEqual(['elevatorCode', 'deviceCode', 'factoryNumber'])
    expect(form.elevatorAdministerPhone).toBeUndefined()
  })

  it('平台占位值（null/undefined/脱敏 *）不写入必填字段', () => {
    const form = { elevatorCode: '', deviceCode: 'KEEP', registrationCode: '', factoryNumber: '' }
    const filled = applyPlatformElevator(form, {
      elevatorCode: 'null', deviceCode: 'undefined', registrationCode: '**', factoryNumber: ''
    })

    expect(filled).toEqual([])
    expect(form).toEqual({ elevatorCode: '', deviceCode: 'KEEP', registrationCode: '', factoryNumber: '' })
  })

  it('平台记录为空时不改动表单', () => {
    const form = { elevatorCode: 'LOCAL-1', deviceCode: '', registrationCode: '', factoryNumber: '' }
    expect(applyPlatformElevator(form, null)).toEqual([])
    expect(form.elevatorCode).toBe('LOCAL-1')
    expect(form.deviceCode).toBe('')
  })
})
