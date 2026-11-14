import { describe, it, expect } from 'vitest'
import { mockData, checkInvariants } from '../mock-data'
import { provider } from './provider'

describe('mock baseline invariants', () => {
  it('all 9 invariants pass', () => {
    const results = checkInvariants(mockData)
    expect(results.length).toBe(9)
    results.forEach(r => expect(r.ok, r.detail).toBe(true))
  })
  it('elevator count closes to 20', () => {
    expect(mockData.elevators.length).toBe(20)
    const sum = mockData.projects.reduce((s, p) => s + p.elevatorCount, 0)
    expect(sum).toBe(20)
  })
})

describe('provider', () => {
  it('getKPIs returns 7 KPI items with consistent totals', () => {
    const kpi = provider.getKPIs('ALL')
    expect(kpi.length).toBe(7)
    expect(kpi.find(k => k.key === 'elevators').value).toBe(20)
  })
  it('getKPIs narrows by scope', () => {
    const all = provider.getKPIs('ALL').find(k => k.key === 'elevators').value
    const inst = provider.getKPIs('INST_MGR').find(k => k.key === 'elevators').value
    expect(inst).toBeLessThanOrEqual(all)
  })
})