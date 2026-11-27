import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

describe('电梯台账提前保养入口', () => {
  const src = readFileSync('src/views/ElevatorsView.vue', 'utf8')

  it('行内操作含 order:dispatch 权限的提前保养按钮', () => {
    expect(src).toContain('label="提前保养"')
    expect(src).toContain('code="order:dispatch"')
  })

  it('非停用电梯才显示入口（INACTIVE 不可派单）', () => {
    expect(src).toContain("v-if=\"row.status !== 'INACTIVE'\"")
  })

  it('确认后调用 dispatchElevator 手动派单', () => {
    expect(src).toContain('dispatchElevator(current.value.id)')
  })

  it('带二次确认弹窗', () => {
    expect(src).toContain('title="提前保养"')
    expect(src).toContain('confirm-label="确认提前保养"')
  })
})
