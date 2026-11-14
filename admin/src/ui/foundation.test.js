import { describe, it, expect } from 'vitest'
import { statusColor } from './status-colors'
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
})

describe('icons', () => {
  it('returns inline svg with no external href', () => {
    const svg = icon('dashboard', 16)
    expect(svg).toContain('<svg')
    expect(svg).not.toContain('href="http')
    expect(svg).toContain('width="16"')
  })
  it('unknown icon falls back to a default glyph', () => {
    expect(icon('nope', 16)).toContain('<svg')
  })
})