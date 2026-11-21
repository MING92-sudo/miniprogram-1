// 一键报修页回归：客户只能选择本单位电梯；选择值用于提交；扫码入口不被挤压。
const assert = require('assert')
const fs = require('fs')
const path = require('path')
const Module = require('module')

const root = path.resolve(__dirname, '..')
const pagePath = path.join(root, 'pages/order/report/index.js')
const ownElevators = [
  { id: 'el_1', elevatorCode: 'EL-001', elevatorName: '1 栋客梯', projectName: '启元小区' },
  { id: 'el_2', elevatorCode: 'EL-002', elevatorName: '2 栋客梯', projectName: '启元小区' }
]
let definition
let loadCalls = 0
let submitted = null

const unitService = {
  getUnitElevators() {
    loadCalls += 1
    return Promise.resolve(ownElevators)
  },
  reportUnitFault(payload) {
    submitted = payload
    return new Promise(() => {})
  }
}

const originalLoad = Module._load
Module._load = function (request, parent, isMain) {
  if (parent && parent.filename === pagePath) {
    if (request === '../../../services/fault') return { reportFault: unitService.reportUnitFault }
    if (request === '../../../services/unit') return unitService
    if (request === '../../../services/elevator') return { getElevatorByCode: () => Promise.resolve(ownElevators[0]) }
    if (request === '../../../services/upload') return { uploadImage: () => Promise.resolve({ url: '' }) }
    if (request === '../../../constants/index') return { FAULT_TYPE_OPTIONS: ['一般故障', '困人急修'], FAULT_TYPE_TRAPPED: '困人急修' }
    if (request === '../../../utils/auth') return { isCustomerPrincipal: () => true }
    if (request === '../../../utils/guard') return { definePage: (route, page) => { definition = page } }
    if (request === '../../../config/routes') return { ROUTES: { '/pages/order/report/index': {} } }
    if (request === '../../../utils/nav') return { back() {}, open() {} }
  }
  return originalLoad.call(this, request, parent, isMain)
}

global.wx = { showToast() {}, scanCode() {} }
require(pagePath)
Module._load = originalLoad

function createPage() {
  return Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch) { this.data = Object.assign({}, this.data, patch) }
  })
}

async function main() {
  assert(definition, '报修页应完成注册')
  const page = createPage()
  page.onLoad({})
  await new Promise((resolve) => setImmediate(resolve))

  assert.strictEqual(loadCalls, 1, '客户进入报修页必须加载本单位电梯')
  assert.strictEqual(page.data.isCustomer, true)
  assert.strictEqual(page.data.elevatorOptions.length, 2, '选择器应展示本单位两部电梯')
  assert(page.data.elevatorOptions[0].includes('EL-001'), '选项应包含可辨识的电梯编码')

  page.onElevatorChange({ detail: { value: '1' } })
  assert.strictEqual(page.data.elevatorCode, 'EL-002')
  assert.strictEqual(page.data.elevator.id, 'el_2')

  page.setData({ desc: '电梯关门时有明显异响' })
  page.onSubmit()
  assert(submitted, '选择电梯后应能提交报修')
  assert.strictEqual(submitted.elevatorCode, 'EL-002', '提交值必须来自所选本单位电梯')

  const wxml = fs.readFileSync(path.join(root, 'pages/order/report/index.wxml'), 'utf8')
  assert(wxml.includes('range="{{elevatorOptions}}"'), '客户电梯应使用选择器')
  assert(wxml.includes('bindchange="onElevatorChange"'), '选择器应绑定选择事件')
  assert(wxml.includes('wx:if="{{isCustomer}}"'), '仅客户主体使用本单位电梯选择器')
  assert(wxml.includes('rp-scan-btn'), '扫码按钮应有独立布局类')

  const wxss = fs.readFileSync(path.join(root, 'pages/order/report/index.wxss'), 'utf8')
  const scanRule = wxss.match(/\.rp-scan-btn\{([^}]*)\}/)
  assert(scanRule, '扫码按钮应定义独立布局')
  assert(scanRule[1].includes('flex:0 0'), '扫码按钮不得被输入区或选择器压缩')
  assert(scanRule[1].includes('box-sizing:border-box'), '按钮尺寸应包含内边距')
  assert(scanRule[1].includes('white-space:nowrap'), '扫码文字不得换行')

  console.log('一键报修页回归通过：单位电梯选择、选择值提交、扫码按钮布局')
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
