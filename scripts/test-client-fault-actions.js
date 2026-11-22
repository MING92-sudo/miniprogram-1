// 客户急修详情不得调用员工专属作业端点。
const assert = require('assert')
const path = require('path')
const Module = require('module')
const pagePath = path.resolve(__dirname, '../pages/order/detail/index.js')
let definition
const calls = []
const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === pagePath) {
    if (request === '../../../services/fault') {
      const call = (name) => (...args) => { calls.push(name); return Promise.resolve({}) }
      return { getFaultDetail: call('detail'), updateFault: call('update'), grabFault: call('grab'),
        arriveFault: call('arrive'), submitFault: call('submit'), confirmFault: call('confirm'), cancelFault: call('cancel') }
    }
    if (request === '../../../services/unit') {
      const call = (name) => (...args) => { calls.push(name); return Promise.resolve({}) }
      return { getUnitFaultDetail: call('unitDetail'), confirmUnitFault: call('unitConfirm'), cancelUnitFault: call('unitCancel') }
    }
    if (request === '../../../utils/guard') return { definePage: (route, page) => { definition = page } }
    if (request === '../../../config/routes') return { ROUTES: {} }
    if (request === '../../../utils/auth') return { getRoles: () => ['CLIENT_ADMIN'], isCustomerPrincipal: () => true }
    if (request === '../../../utils/nav') return { back() {}, open() {} }
  }
  return originalLoad.call(this, request, parent, isMain)
}
global.wx = { showToast() {}, showModal() {}, getLocation() {} }
require(pagePath)
Module._load = originalLoad

const page = Object.assign({}, definition, {
  data: JSON.parse(JSON.stringify(definition.data)),
  setData(patch) { this.data = Object.assign({}, this.data, patch) }
})
page.setData({ id: 'f1', isClient: true, siteDesc: 'x', handleDesc: 'y', submitting: false })
page.onGrab()
page.onArrive()
page.onSaveProcess()
page.onSubmitFault()
assert.deepStrictEqual(calls, [], '客户页面调用了员工专属作业端点: ' + calls.join(','))
console.log('PASS 客户急修详情不触发员工接单/到场/维修提交')
