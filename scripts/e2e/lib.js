'use strict'
/**
 * E2E 测试内核：HTTP 客户端 + 断言 + 结果收集。
 *
 * 后端契约（GlobalExceptionHandler）：业务异常返回 **HTTP 200 + body.code != 0**，
 * 因此断言看 body.code，不看 HTTP status；HTTP 5xx 或 body.code >= 500 一律判 FAIL。
 */

const BASE = process.env.E2E_BASE || 'http://localhost:8080'

const results = []
let currentSuite = '未分组'

function suite(name) {
  currentSuite = name
}

const colors = {
  green: (s) => `\x1b[32m${s}\x1b[0m`,
  red: (s) => `\x1b[31m${s}\x1b[0m`,
  yellow: (s) => `\x1b[33m${s}\x1b[0m`,
  gray: (s) => `\x1b[90m${s}\x1b[0m`,
  cyan: (s) => `\x1b[36m${s}\x1b[0m`
}

/** 单次 HTTP 调用。返回 { http, code, message, data, ms, text } */
async function call(method, path, opts = {}) {
  const { token, body, headers = {}, timeout = 20000 } = opts
  const h = { 'Content-Type': 'application/json', ...headers }
  if (token) h.Authorization = 'Bearer ' + token
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), timeout)
  const t0 = Date.now()
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: h,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: ctrl.signal
    })
    const text = await res.text()
    let data = null
    try {
      data = text ? JSON.parse(text) : null
    } catch (e) {
      data = null
    }
    return {
      http: res.status,
      code: data && typeof data.code === 'number' ? data.code : null,
      message: (data && data.message) || '',
      data: data && data.data,
      ms: Date.now() - t0,
      text: text && text.length > 400 ? text.slice(0, 400) : text
    }
  } catch (e) {
    return {
      http: 0,
      code: -1,
      message: 'NETWORK: ' + (e && e.message),
      data: null,
      ms: Date.now() - t0,
      text: ''
    }
  } finally {
    clearTimeout(timer)
  }
}

const get = (p, t, o = {}) => call('GET', p, { token: t, ...o })
const post = (p, b, t, o = {}) => call('POST', p, { token: t, body: b === undefined ? {} : b, ...o })
const put = (p, b, t, o = {}) => call('PUT', p, { token: t, body: b === undefined ? {} : b, ...o })
const del = (p, t, o = {}) => call('DELETE', p, { token: t, ...o })
const patch = (p, b, t, o = {}) => call('PATCH', p, { token: t, body: b === undefined ? {} : b, ...o })

/**
 * 记录一条用例结果。
 * expect:
 *   'ok'        必须 code === 0
 *   'biz'       必须是业务拒绝（code 为 4xx/14xx，且 != 0）
 *   'forbidden' 必须 code === 403 或 1403
 *   数字        必须等于该 code
 *   'any'       只要求不是 5xx / 网络错误
 */
function record(name, r, expect = 'any', note = '') {
  const isNetwork = r.http === 0
  // 业务码是 4 位数（1001/1403/2001…），不能按 >=500 判 5xx，否则 2001「未配置」会被误判为服务端异常
  const is5xx = r.http >= 500 || (r.code !== null && r.code >= 500 && r.code < 1000)
  let ok = false
  let verdict = ''

  if (expect === 'ok') {
    ok = !isNetwork && !is5xx && r.code === 0
    verdict = ok ? 'PASS' : `期望成功，实际 code=${r.code} ${r.message}`
  } else if (expect === 'biz') {
    ok = !isNetwork && !is5xx && r.code !== 0 && r.code !== null
    verdict = ok ? `PASS(biz ${r.code})` : `期望业务拒绝，实际 code=${r.code} ${r.message}`
  } else if (expect === 'forbidden') {
    ok = !isNetwork && (r.code === 403 || r.code === 1403)
    verdict = ok ? 'PASS(403)' : `期望 403，实际 code=${r.code} ${r.message}`
  } else if (typeof expect === 'number') {
    ok = !isNetwork && r.code === expect
    verdict = ok ? `PASS(${expect})` : `期望 ${expect}，实际 code=${r.code} ${r.message}`
  } else {
    ok = !isNetwork && !is5xx
    verdict = ok ? `OK(${r.code})` : `服务端异常 http=${r.http} code=${r.code} ${r.message}`
  }

  results.push({
    suite: currentSuite,
    name,
    expect,
    ok,
    http: r.http,
    code: r.code,
    message: r.message,
    ms: r.ms,
    note,
    verdict
  })

  const tag = ok ? colors.green('✓') : colors.red('✗')
  const detail = ok && expect === 'any' ? colors.gray(verdict) : ok ? colors.gray(verdict) : colors.red(verdict)
  console.log(`  ${tag} ${name} ${detail}${note ? colors.gray(' — ' + note) : ''}`)
  return ok
}

/** 断言辅助：直接抛错，用于业务流里的硬校验 */
function assert(cond, msg) {
  if (!cond) throw new Error('ASSERT: ' + msg)
}

function assertEq(actual, expected, msg) {
  if (actual !== expected) {
    throw new Error(`ASSERT: ${msg} 期望 ${JSON.stringify(expected)}，实际 ${JSON.stringify(actual)}`)
  }
}

/** 登录并返回 { token, ...payload } */
async function login(phone, password, extra = {}) {
  const r = await post('/auth/login', { phone, password, ...extra })
  if (!r.data || r.code !== 0) {
    throw new Error(`登录失败 ${phone}: code=${r.code} ${r.message}`)
  }
  return r.data
}

function summary() {
  const total = results.length
  const failed = results.filter((x) => !x.ok)
  const bySuite = {}
  for (const r of results) {
    bySuite[r.suite] = bySuite[r.suite] || { total: 0, pass: 0 }
    bySuite[r.suite].total++
    if (r.ok) bySuite[r.suite].pass++
  }
  return { total, pass: total - failed.length, fail: failed.length, failed, bySuite }
}

function printSummary() {
  const s = summary()
  console.log('\n' + colors.cyan('════════ 汇总 ════════'))
  for (const [k, v] of Object.entries(s.bySuite)) {
    const bad = v.total - v.pass
    const line = `${v.pass}/${v.total}`
    console.log(`  ${bad === 0 ? colors.green(line) : colors.red(line)}  ${k}`)
  }
  console.log(
    `\n  总计 ${s.total} 项，通过 ${colors.green(s.pass)}，失败 ${
      s.fail ? colors.red(s.fail) : colors.green(0)
    }`
  )
  if (s.failed.length) {
    console.log('\n' + colors.red('失败用例：'))
    for (const f of s.failed) {
      console.log(`  - [${f.suite}] ${f.name}: ${f.verdict}`)
    }
  }
  return s
}

module.exports = {
  BASE,
  call,
  get,
  post,
  put,
  del,
  patch,
  record,
  assert,
  assertEq,
  login,
  suite,
  summary,
  printSummary,
  results,
  colors
}
