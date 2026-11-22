// 登录流程契约校验：wx-login 未绑定 422、bind-wechat 形状（mock 与后端 AuthController 同口径）
const path = require('path')
const assert = require('assert')
const ROOT = path.join(__dirname, '..')

global.wx = {
  getStorageSync: () => null,
  setStorageSync: () => {},
  removeStorageSync: () => {}
}
const { mockRequest } = require(path.join(ROOT, 'mock/server.js'))

function req(method, url, data) {
  return mockRequest({ method, url, data })
}

/** 断言请求以指定业务码失败（mock 与后端同口径：throw {code,message}） */
async function expectCode(code, method, url, data) {
  try {
    const r = await req(method, url, data)
    throw new assert.AssertionError({ message: '期望 ' + code + '，实际成功返回 ' + JSON.stringify(r).slice(0, 120) })
  } catch (e) {
    if (e instanceof assert.AssertionError) throw e
    assert.strictEqual(e && e.code, code, '期望 code=' + code + '，实际 ' + JSON.stringify(e))
    return e
  }
}

const results = []
async function check(name, fn) {
  try { await fn(); results.push({ name, ok: true }) } catch (e) { results.push({ name, ok: false, err: e.message }) }
}

;(async () => {
  await check('wx-login UNBOUND → 422 未绑定', async () => {
    await expectCode(422, 'POST', '/auth/wx-login', { code: 'UNBOUND' })
  })

  await check('wx-login UNBOUND 422 文案与后端一致', async () => {
    const e = await expectCode(422, 'POST', '/auth/wx-login', { code: 'UNBOUND' })
    assert.strictEqual(e.message, '该微信尚未绑定账号，请先用账号密码登录并绑定微信')
  })

  await check('wx-login 普通 code → 登录载荷', async () => {
    const r = await req('POST', '/auth/wx-login', { code: 'any-code' })
    assert(r && typeof r.token === 'string' && r.token.length > 0, 'wx-login 未返回 token')
  })

  await check('bind-wechat 带 code → ok', async () => {
    const r = await req('POST', '/auth/bind-wechat', { code: 'c1' })
    assert.strictEqual(r.ok, true)
  })

  const failed = results.filter((r) => !r.ok)
  results.forEach((r) => console.log((r.ok ? 'PASS' : 'FAIL') + '  ' + r.name + (r.ok ? '' : '  → ' + r.err)))
  console.log(failed.length ? failed.length + '/' + results.length + ' 失败' : '全部 ' + results.length + ' 项通过')
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('脚本异常', e); process.exit(1) })
