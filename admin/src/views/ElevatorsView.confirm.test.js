import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

// 确认人绑定登录身份（Task 6）：弹窗不再采集确认人编号，提交不携带请求体身份
describe('变更确认绑定登录身份', () => {
  const src = readFileSync('src/views/ElevatorsView.vue', 'utf8')

  it('确认弹窗不含确认人编号输入', () => {
    expect(src).not.toContain('confirmerId')
    expect(src).not.toContain('确认人编号')
  })

  it('提交确认不带请求体', () => {
    expect(src).toContain('confirmChange(confirmId.value)')
  })
})
