import { describe, it, expect } from 'vitest'
import { statusColor, tagClass } from './status-colors'
import { icon } from './icons'

describe('status-colors', () => {
  it('maps progress to amber', () => {
    expect(statusColor('PROCESSING').fg).toBe('var(--amber)')
  })
  it('maps danger to signal', () => {
    expect(statusColor('TRAPPED').fg).toBe('var(--signal)')
  })
  it('maps done to ok', () => {
    expect(statusColor('ARCHIVED').fg).toBe('var(--ok)')
  })
  it('unknown status falls back to muted', () => {
    expect(statusColor('WHATEVER').fg).toBe('var(--muted)')
  })
  it('tagClass maps Chinese prototype states', () => {
    expect(tagClass('已归档')).toBe('ok')
    expect(tagClass('待复核')).toBe('amber')
    expect(tagClass('超时')).toBe('signal')
    expect(tagClass('停用')).toBe('dim')
    expect(tagClass('未知态')).toBe('ghost')
  })
})

describe('icons (sprite use 引用，零外部请求)', () => {
  it('returns svg use referencing inline sprite symbol', () => {
    const svg = icon('dashboard', 16)
    expect(svg).toContain('<svg')
    expect(svg).toContain('#i-gauge') // 旧名 dashboard → 原型 gauge 别名
    expect(svg).not.toContain('href="http')
  })
  it('size argument maps to prototype size class', () => {
    expect(icon('check', 13)).toContain('ic-sm')
    expect(icon('check', 20)).toContain('ic-lg')
    expect(icon('check')).toContain('class="ic"')
  })
  it('unknown icon still renders a sprite use', () => {
    expect(icon('nope')).toContain('<svg')
  })
})