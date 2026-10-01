import { describe, expect, it } from 'vitest'
import { idempotencyKey, resolveEnvelope } from './client'

/** API 信封解包回归：code=0 取 data；业务码抛结构化错误；非信封原样返回 */
describe('resolveEnvelope', () => {
  it('code=0 时返回 data', () => {
    expect(resolveEnvelope({ code: 0, message: 'success', data: { a: 1 } })).toEqual({ a: 1 })
    expect(resolveEnvelope({ code: 0, message: 'success', data: null })).toBeNull()
  })

  it('业务码抛出带 code/data 的错误（1002 conflicts[]）', () => {
    try {
      resolveEnvelope({
        code: 1002,
        message: '手机号互斥冲突',
        data: { conflicts: [{ phone: '13800000001', roles: [{ role: '维保人员', owner: '张伟' }] }] }
      })
      expect.unreachable('应抛出异常')
    } catch (e) {
      expect(e.code).toBe(1002)
      expect(e.data.conflicts).toHaveLength(1)
      expect(e.data.conflicts[0].roles[0].role).toBe('维保人员')
    }
  })

  it('401 标记为登录过期错误', () => {
    try {
      resolveEnvelope({ code: 401, message: '登录已过期' })
      expect.unreachable('应抛出异常')
    } catch (e) {
      expect(e.code).toBe(401)
    }
  })

  it('非信封响应（如 PDF blob）原样返回', () => {
    const raw = { some: 'raw' }
    expect(resolveEnvelope(raw)).toBe(raw)
  })
})

describe('idempotencyKey', () => {
  it('多次生成互不相同', () => {
    const keys = new Set(Array.from({ length: 50 }, () => idempotencyKey()))
    expect(keys.size).toBe(50)
  })
})
