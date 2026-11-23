// 移交整改/复验权限分离 + 资料三平铺字段提交契约回归。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')

const ROOT = path.resolve(__dirname, '..')
const ROUTE = '/pkg-handover/pages/rectify/index'
const RECTIFY_PAGE = path.join(ROOT, 'pkg-handover/pages/rectify/index.js')
const UPLOAD_PAGE = path.join(ROOT, 'pkg-handover/pages/upload/index.js')
const RECTIFY_WXML = fs.readFileSync(path.join(ROOT, 'pkg-handover/pages/rectify/index.wxml'), 'utf8')
const UPLOAD_WXML = fs.readFileSync(path.join(ROOT, 'pkg-handover/pages/upload/index.wxml'), 'utf8')

global.wx = { getStorageSync: () => '' }
const { mockRequest } = require('../mock/server')
delete global.wx

const toasts = []
global.wx = {
  showToast: (o) => toasts.push((o && o.title) || ''),
  showLoading: () => {},
  hideLoading: () => {},
  navigateTo: () => {}
}

function req(method, url, data) {
  return mockRequest({ method, url, data })
}

async function login(phone) {
  return req('POST', '/auth/login', { phone, password: '123456' })
}

async function expectCode(promise, code, label) {
  let error = null
  try { await promise } catch (e) { error = e }
  assert(error, label + '：预期失败但请求成功')
  assert.strictEqual(error.code, code, label + '：期望 ' + code + '，实际 ' + JSON.stringify(error))
}

function tick() { return new Promise((resolve) => setImmediate(resolve)) }

function makePage(definition) {
  const data = JSON.parse(JSON.stringify(definition.data))
  const page = {
    data,
    setData(patch) {
      Object.keys(patch).forEach((key) => {
        if (key.indexOf('.') > -1) {
          const parts = key.split('.')
          this.data[parts[0]][parts[1]] = patch[key]
        } else this.data[key] = patch[key]
      })
    }
  }
  Object.keys(definition).forEach((key) => {
    if (typeof definition[key] === 'function') page[key] = definition[key]
  })
  return page
}

function stubModules(pagePath, stubs) {
  const originalLoad = Module._load
  Module._load = function (request, parent, isMain) {
    if (parent && parent.filename === pagePath && stubs[request]) return stubs[request]
    return originalLoad.call(this, request, parent, isMain)
  }
  try {
    delete require.cache[require.resolve(pagePath)]
    require(pagePath)
  } finally {
    Module._load = originalLoad
  }
}

function loadRectifyPage(perms) {
  let definition
  const calls = { detail: 0, rects: 0, rectify: [], recheck: [] }
  stubModules(RECTIFY_PAGE, {
    '../../../utils/guard': { definePage: (meta, page) => { definition = page }, ensureLogin: () => true },
    '../../../config/routes': { ROUTES: { [ROUTE]: {} } },
    '../../../utils/auth': { getPerms: () => perms, getUserInfo: () => ({ id: 'emp_test', name: '测试员' }) },
    '../../../services/handover': {
      getHandoverDetail: () => { calls.detail += 1; return Promise.resolve({ id: 'ho_1' }) },
      getHandoverRectifications: () => {
        calls.rects += 1
        return Promise.resolve([{ id: 'hr_1', itemNo: 38, itemType: 'G05', recheckResult: null, afterPhotoFileId: '' }])
      },
      rectifyHandoverItem: (...args) => { calls.rectify.push(args); return Promise.resolve({}) },
      recheckHandoverItem: (...args) => { calls.recheck.push(args); return Promise.resolve({}) }
    },
    '../../../services/upload': { uploadImage: () => Promise.resolve({ fileId: 'up', url: '' }) },
    '../../../utils/nav': { back: () => {}, open: () => {} }
  })
  assert(definition, '整改页应通过 definePage 注册')
  return { page: makePage(definition), calls }
}

function loadUploadPage(detail) {
  let definition
  const calls = []
  stubModules(UPLOAD_PAGE, {
    '../../../utils/guard': { definePage: (meta, page) => { definition = page } },
    '../../../config/routes': { ROUTES: {} },
    '../../../services/handover': {
      getHandoverDetail: () => Promise.resolve(detail),
      submitHandoverDocs: (id, body) => { calls.push({ id, body }); return Promise.resolve({}) }
    },
    '../../../services/upload': { uploadImage: () => Promise.resolve({ fileId: 'up', url: '' }) },
    '../../../utils/nav': { back: () => {} }
  })
  assert(definition, '资料上传页应通过 definePage 注册')
  return { page: makePage(definition), calls }
}

;(async () => {
  // ── 路由元数据：整改与复验同页，任一权限即可进入 ──
  const { ROUTES } = require('../config/routes')
  const meta = ROUTES[ROUTE]
  assert(meta, '整改页必须注册路由')
  assert.deepStrictEqual(meta.perm, ['handover:rectify', 'handover:inspect'], '整改页须同时接受整改与复验权限（any-of）')
  ;['INST_PM', 'INST_CLERK', 'INSTALLER', 'MAINT_TECH'].forEach((role) => {
    assert(meta.roles.indexOf(role) > -1, '整改页角色须包含 ' + role)
  })
  const { canAccess } = require('../utils/guard')
  assert.strictEqual(canAccess(meta, { token: 't', roles: ['INST_PM'], perms: ['handover:rectify'] }), true, 'INST_PM 应可进整改页')
  assert.strictEqual(canAccess(meta, { token: 't', roles: ['MAINT_TECH'], perms: ['handover:inspect'] }), true, 'MAINT_TECH 应可进整改页')
  assert.strictEqual(canAccess(meta, { token: 't', roles: ['CLIENT_ADMIN'], perms: ['client:sign'] }), false, '客户不得进整改页')

  // ── 页面门禁：整改/复验按权限分别放行 ──
  const instPm = loadRectifyPage(['handover:rectify'])
  await instPm.page.onLoad({ id: 'ho_1' })
  await tick()
  assert.strictEqual(instPm.page.data.canRectify, true, 'INST_PM 可整改')
  assert.strictEqual(instPm.page.data.canRecheck, false, 'INST_PM 无复验权限')
  await instPm.page.onRecheck({ currentTarget: { dataset: { idx: 0, result: 'PASS' } } })
  assert.strictEqual(instPm.calls.recheck.length, 0, '无复验权限不得调用复验接口')
  instPm.page.data.rects[0].rectMeasure = '紧固张紧轮'
  const rectifyFirst = instPm.page.onRectify({ currentTarget: { dataset: { idx: 0 } } })
  const rectifySecond = instPm.page.onRectify({ currentTarget: { dataset: { idx: 0 } } })
  await Promise.all([rectifyFirst, rectifySecond])
  assert.strictEqual(instPm.calls.rectify.length, 1, '提交期间重复点击只请求一次')

  const maintTech = loadRectifyPage(['handover:inspect'])
  await maintTech.page.onLoad({ id: 'ho_1' })
  await tick()
  assert.strictEqual(maintTech.page.data.canRectify, false, 'MAINT_TECH 无整改权限')
  assert.strictEqual(maintTech.page.data.canRecheck, true, 'MAINT_TECH 可复验')
  await maintTech.page.onRectify({ currentTarget: { dataset: { idx: 0 } } })
  assert.strictEqual(maintTech.calls.rectify.length, 0, '无整改权限不得调用整改接口')
  maintTech.page.data.rects[0].afterPhotoFileId = 'file_after'
  const recheckFirst = maintTech.page.onRecheck({ currentTarget: { dataset: { idx: 0, result: 'PASS' } } })
  const recheckSecond = maintTech.page.onRecheck({ currentTarget: { dataset: { idx: 0, result: 'PASS' } } })
  await Promise.all([recheckFirst, recheckSecond])
  assert.strictEqual(maintTech.calls.recheck.length, 1, '复验提交期间重复点击只请求一次')

  const installer = loadRectifyPage(['handover:rectify', 'handover:inspect'])
  await installer.page.onLoad({ id: 'ho_1' })
  await tick()
  assert.strictEqual(installer.page.data.canRectify, true, 'INSTALLER 可整改')
  assert.strictEqual(installer.page.data.canRecheck, true, 'INSTALLER 可复验')

  // WXML 操作区按权限与整改状态分别渲染
  assert(/wx:if="\{\{canRectify && !item\.afterPhotoFileId\}\}"/.test(RECTIFY_WXML), '整改操作须仅对整改权限且未提交整改的项显示')
  assert(/wx:elif="\{\{canRecheck && item\.afterPhotoFileId\}\}"/.test(RECTIFY_WXML), '复验操作须仅对复验权限且已整改的项显示')
  assert((RECTIFY_WXML.match(/disabled="\{\{submitting\}\}"/g) || []).length >= 2, '整改与复验按钮提交期间须禁用')

  // ── 资料上传页：三平铺字段，缺件/标记缺件都不请求 ──
  const allDocs = { supervisionReportFileId: 'f1', maintenanceManualFileId: 'f2', qualityCertificateFileId: 'f3' }

  const ok = loadUploadPage(Object.assign({}, allDocs))
  ok.page.setData({ id: 'ho_1' })
  await ok.page.load()
  assert.strictEqual(ok.page.data.docs.length, 3, '资料页须固定展示三份强制资料')
  const okFirst = ok.page.onSubmit()
  const okSecond = ok.page.onSubmit()
  await Promise.all([okFirst, okSecond])
  assert.strictEqual(ok.calls.length, 1, '资料齐全应提交一次，重复点击不重复请求')
  assert.deepStrictEqual(ok.calls[0].body, {
    supervisionReportFileId: 'f1', maintenanceManualFileId: 'f2', qualityCertificateFileId: 'f3'
  }, '提交体须为三个平铺字段')
  assert.strictEqual(Object.hasOwn(ok.calls[0].body, 'docs'), false, '不得再发送后端不接受的 docs 数组')

  const missing = loadUploadPage({ supervisionReportFileId: 'f1', maintenanceManualFileId: '', qualityCertificateFileId: 'f3' })
  missing.page.setData({ id: 'ho_1' })
  await missing.page.load()
  assert.strictEqual(missing.page.data.docs.length, 3)
  await missing.page.onSubmit()
  assert.strictEqual(missing.calls.length, 0, '缺任一文件不得提交')

  assert.strictEqual(UPLOAD_WXML.includes('标记缺件'), false, '强制资料页不得保留标记缺件死入口')
  assert.strictEqual(UPLOAD_WXML.includes('容缺受理'), false, '不得宣称后端不支持的容缺受理')
  assert(UPLOAD_WXML.includes('三项强制资料上传齐全后才可提交'), '上传页须明确三份资料齐全后才可提交')

  // ── mock 与真实后端权限同步：整改/复验互斥 ──
  await login('13800000013') // INST_PM
  assert.ok(await req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/rectify', { rectMeasure: '紧固' }), 'INST_PM 可整改')
  await expectCode(req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/recheck', { result: 'PASS' }), 1403, 'INST_PM 不得复验')

  await login('13800000011') // MAINT_TECH
  await expectCode(req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/rectify', { rectMeasure: '紧固' }), 1403, 'MAINT_TECH 不得整改')
  assert.ok(await req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/recheck', { result: 'PASS' }), 'MAINT_TECH 可复验')
  await expectCode(req('POST', '/handovers/ho_demo_2/submit-docs', allDocs), 1403, 'MAINT_TECH 不得提交资料')

  await login('13800000017') // INSTALLER
  assert.ok(await req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/rectify', { rectMeasure: '紧固' }), 'INSTALLER 可整改')
  assert.ok(await req('POST', '/handovers/ho_demo_2/rectifications/hr_demo_2/recheck', { result: 'PASS' }), 'INSTALLER 可复验')

  console.log('PASS 整改/复验权限分离与资料三平铺字段提交契约')
})().catch((e) => { console.error(e); process.exit(1) })
