import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const tokens = readFileSync(resolve('src/ui/tokens.css'), 'utf8')
const admin = readFileSync(resolve('src/ui/admin.css'), 'utf8')

function token(name) {
  const match = tokens.match(new RegExp(`--${name}:\\s*(#[0-9A-Fa-f]{6})\\s*;`))
  if (!match) throw new Error(`缺少颜色变量 --${name}`)
  return match[1]
}

function luminance(hex) {
  const channels = hex.slice(1).match(/.{2}/g).map((part) => parseInt(part, 16) / 255)
  const linear = channels.map((value) => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
}

function contrast(a, b) {
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

describe('管理端浅色工作区', () => {
  it('页面和卡片使用浅色背景，正文及辅助文字保持可读对比度', () => {
    for (const background of [token('ink'), token('panel')]) {
      expect(luminance(background)).toBeGreaterThan(0.85)
      expect(contrast(token('text'), background)).toBeGreaterThanOrEqual(7)
      expect(contrast(token('muted'), background)).toBeGreaterThanOrEqual(4.5)
      expect(contrast(token('dim'), background)).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('顶栏、卡片、表单和浮层同步切换为浅色，不仅修改 body 背景', () => {
    expect(admin).toMatch(/\.top\{[^}]*background:rgba\(255,255,255,\.96\)/s)
    expect(admin).toMatch(/\.card\{background:#FFFFFF/)
    expect(admin).toMatch(/\.ctl\{background:#FFFFFF/)
    expect(admin).toMatch(/\.modal\{background:#FFFFFF/)
    expect(admin).toMatch(/\.drawer\{background:#FFFFFF/)
  })

  it('深色侧栏和调度大屏显式保留深色文字变量', () => {
    expect(admin).toMatch(/\.side\{[^}]*--text:#E8EEF0/s)
    expect(admin).toMatch(/\.side\{[^}]*background:linear-gradient\(180deg,#304157,#2B3B50\)/s)
    expect(admin).toMatch(/\.board\{[^}]*--text:#E8EEF0/s)
  })
})
