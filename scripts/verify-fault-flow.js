const assert = require('assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.join(__dirname, '..')
global.wx = {
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {}
}
const { mockRequest } = require(path.join(ROOT, 'mock/server.js'))
const d = require(path.join(ROOT, 'mock/data.js'))

async function expectCode(code, data) {
  try {
    await mockRequest({ method: 'POST', url: '/faults/ft_verify/submit', data })
    assert.fail('期望业务码 ' + code)
  } catch (e) {
    assert.strictEqual(e && e.code, code)
  }
}

;(async () => {
  const fault = { id: 'ft_verify', elevatorCode: 'EM-VERIFY', status: 'PROCESSING', photos: [] }
  d.db.faults.push(fault)
  try {
    await mockRequest({
      method: 'POST',
      url: '/faults/ft_verify/submit',
      data: { siteDesc: '门锁故障', todoDesc: '复查门触点', handleDesc: '更换触点' }
    })
    assert.strictEqual(fault.status, 'PENDING_CONFIRM')
    assert.strictEqual(fault.faultReason, '门锁故障')
    assert.strictEqual(fault.siteDesc, '门锁故障')
    assert.strictEqual(fault.todoDesc, '复查门触点')
    assert.strictEqual(fault.handleDesc, '更换触点')
    assert(fault.finishedAt, '提交必须记录完成时间')

    fault.status = 'PROCESSING'
    await expectCode(422, { siteDesc: '', handleDesc: '更换触点' })

    const page = fs.readFileSync(path.join(ROOT, 'pages/order/detail/index.js'), 'utf8')
    const saveProcess = page.match(/onSaveProcess\(\)[\s\S]*?onSubmitFault\(\)/)
    assert(saveProcess, '未找到保存维修过程方法')
    assert(!saveProcess[0].includes('finishedAt:'), '草稿保存不得写维修完成时间')

    console.log('急修提交契约与草稿完成时间检查通过')
  } finally {
    const index = d.db.faults.findIndex((f) => f.id === fault.id)
    if (index >= 0) d.db.faults.splice(index, 1)
  }
})().catch((e) => {
  console.error(e)
  process.exitCode = 1
})
