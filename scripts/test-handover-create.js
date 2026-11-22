// 移交起草页回归：使用单位选项 + 三资料同页上传 + 缺件/上传失败不创建。
const assert = require('assert')
const Module = require('module')
const path = require('path')

const pagePath = path.resolve(__dirname, '../pkg-handover/pages/create/index.js')
// mock 路由初始化时以 INST_PM 角色加载，后续页面测试再覆盖 wx。
global.wx = { getStorageSync: (key) => key === 'em_roles' ? ['INST_PM'] : '' }
const { mockRequest } = require('../mock/server')
delete global.wx
const originalLoad = Module._load
let definition
const createCalls = []
const toasts = []
let uploadShouldFail = false

Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === pagePath) {
    if (request === '../../../utils/guard') return { definePage: (route, page) => { definition = page }, ensureLogin: () => true }
    if (request === '../../../config/routes') return { ROUTES: {} }
    if (request === '../../../services/handover') {
      return {
        getHandoverOptions: () => Promise.resolve({ useUnits: [{ id: 'uu_1', unitName: '世纪物业' }] }),
        initHandover: (body) => {
          createCalls.push(body)
          return Promise.resolve({ id: 'ho_1', status: 'PENDING_INST_REVIEW' })
        }
      }
    }
    if (request === '../../../services/upload') {
      return { uploadImage: () => uploadShouldFail
        ? Promise.reject(new Error('upload failed'))
        : Promise.resolve({ fileId: 'file_new', url: '' }) }
    }
  }
  return originalLoad.call(this, request, parent, isMain)
}

global.wx = {
  showToast: (o) => toasts.push(o.title || ''),
  showLoading: () => {},
  hideLoading: () => {},
  redirectTo: () => {},
  showActionSheet: () => {}
}
global.getApp = () => ({ globalData: { perms: ['handover:create'], userInfo: { id: 'emp_1', name: '安装经理' } } })
require(pagePath)
Module._load = originalLoad

function makePage() {
  const data = JSON.parse(JSON.stringify(definition.data))
  const page = {
    data,
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        if (key.startsWith('form.')) this.data.form[key.slice(5)] = value
        else this.data[key] = value
      }
    }
  }
  for (const [key, value] of Object.entries(definition)) {
    if (typeof value === 'function') page[key] = value
  }
  return page
}

function fillValidForm(page) {
  Object.assign(page.data.form, {
    projectName: '世纪大厦', useUnitId: 'uu_1', factoryNumber: 'SGL-2026-001', insideNumber: 'KT-01',
    elevatorCode: '', regCode: '', deviceCode: ''
  })
  for (const doc of page.data.docs) doc.fileId = 'file_' + doc.field
}

;(async () => {
  const page = makePage()
  await page.onLoad()
  assert.strictEqual(page.data.useUnitOptions.length, 1, '应加载使用单位选项')
  assert.strictEqual(page.data.docs.length, 3, '应要求三份资料')

  fillValidForm(page)
  await page.onSubmit()
  assert.strictEqual(createCalls.length, 1, '资料齐全应创建一次')
  const body = createCalls[0]
  assert.strictEqual(body.useUnitId, 'uu_1')
  assert.strictEqual(body.supervisionReportFileId, 'file_supervisionReportFileId')
  assert.strictEqual(body.maintenanceManualFileId, 'file_maintenanceManualFileId')
  assert.strictEqual(body.qualityCertificateFileId, 'file_qualityCertificateFileId')
  assert.strictEqual(body.factoryNumber, 'SGL-2026-001')
  assert.strictEqual(Object.hasOwn(body, 'elevatorId'), false, '客户端不得提交 elevatorId')

  const missingPage = makePage()
  await missingPage.onLoad()
  fillValidForm(missingPage)
  missingPage.data.docs[0].fileId = ''
  await missingPage.onSubmit()
  assert.strictEqual(createCalls.length, 1, '缺任一资料不得继续创建')

  uploadShouldFail = true
  const failedPage = makePage()
  await failedPage.onLoad()
  await failedPage.uploadDoc('supervisionReportFileId', '/tmp/bad.pdf')
  assert.strictEqual(createCalls.length, 1, '上传失败不得创建移交单')
  assert.strictEqual(failedPage.data.docs[0].fileId, '', '失败文件不得标成已上传')

  await assert.rejects(
    mockRequest({ method: 'POST', url: '/handovers', data: {
      useUnitId: require('../mock/data').db.useUnits[0].id,
      supervisionReportFileId: 'f1', maintenanceManualFileId: 'f2', qualityCertificateFileId: 'f3'
    } }),
    (e) => e && e.code === 422,
    'mock 缺 factoryNumber 必须与真实后端一致返回 422'
  )

  console.log('PASS 移交起草：单位选项、三资料必传、缺件/上传失败不创建')
})().catch((e) => { console.error(e); process.exit(1) })
