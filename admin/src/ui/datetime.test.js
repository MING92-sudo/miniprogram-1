import { describe, it, expect } from 'vitest'
import { parseTime, minutesBetween, todayGmt8, daysBetween } from './datetime'

describe('GMT+8 时间解析（后端墙钟文本）', () => {
  it('空格分隔文本按 GMT+8 解析，不依赖浏览器时区', () => {
    expect(parseTime('2026-11-24 09:00:00')).toBe(Date.UTC(2026, 10, 24, 1, 0, 0))
    expect(parseTime('2026-11-24 09:00')).toBe(Date.UTC(2026, 10, 24, 1, 0, 0))
    expect(parseTime('2026-11-24')).toBe(Date.UTC(2026, 10, 23, 16, 0, 0))
  })
  it('兼容 Date / 时间戳 / 非标准字符串', () => {
    const d = new Date(1700000000000)
    expect(parseTime(d)).toBe(1700000000000)
    expect(parseTime(1700000000000)).toBe(1700000000000)
    expect(parseTime('2026-11-24T09:00:00+08:00')).toBe(Date.UTC(2026, 10, 24, 1, 0, 0))
    expect(Number.isNaN(parseTime(''))).toBe(true)
  })
  it('分钟差与天差', () => {
    expect(minutesBetween('2026-11-24 09:00:00', '2026-11-24 10:30:00')).toBe(90)
    expect(daysBetween('2026-11-01', '2026-11-24')).toBe(23)
    expect(daysBetween('2026-11-24', '2026-11-24')).toBe(0)
  })
  it('todayGmt8 输出 yyyy-MM-dd', () => {
    expect(todayGmt8()).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })
})
