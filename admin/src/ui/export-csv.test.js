import { describe, it, expect, vi, beforeEach } from 'vitest'
import { exportCsv } from './export-csv'

describe('CSV 导出公式注入防护', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  it('中和所有公式起始字符并保留正常 CSV 转义', async () => {
    const blob = await new Promise((resolve) => {
      const OriginalBlob = globalThis.Blob
      globalThis.Blob = class extends OriginalBlob {
        constructor(parts, options) { super(parts, options); resolve(this) }
      }
      exportCsv('测试', [{ key: 'v', label: '值' }], [
        { v: '=1+1' }, { v: '+SUM(A1:A2)' }, { v: '-2' }, { v: '@cmd' }, { v: '普通文本' }
      ])
      globalThis.Blob = OriginalBlob
    })
    const text = await blob.text()
    expect(text).toContain("'=1+1")
    expect(text).toContain("'+SUM(A1:A2)")
    expect(text).toContain("'-2")
    expect(text).toContain("'@cmd")
    expect(text).toContain('普通文本')
  })
})
