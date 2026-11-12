// 发起外接建档（W2，docs/04 A.10）：选使用单位 + 已生效合同（合同前置）→ 填电梯快照 →
// 提交后进入 DOC_COLLECTING，资料在详情页上传/标缺件（ExternalFilingService.init 校验合同与同梯冲突）
const { initFiling, getUseUnits, getActiveContracts } = require('../../services/filing')
const {
  ELEVATOR_CATEGORIES,
  WORK_TYPE_CODES,
  FILING_WRITE_ROLES,
  FILING_TEXT,
  TOAST_TEXT
} = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

const WORK_TYPE_CODES_LIST = Object.keys(WORK_TYPE_CODES)
const WORK_TYPE_LABELS = WORK_TYPE_CODES_LIST.map(function (c) {
  return c + ' ' + WORK_TYPE_CODES[c]
})

function emptyForm() {
  return {
    projectName: '',
    elevatorCode: '',
    elevatorName: '',
    category: '',
    location: '',
    regCode: '',
    deviceCode: '',
    insideNumber: '',
    model: '',
    brand: '',
    manufacturer: '',
    productNo: '',
    driveMode: '',
    ratedLoad: '',
    ratedLoadUnit: 'kg',
    ratedSpeed: '',
    ratedSpeedUnit: 'm/s',
    stationsDoors: '',
    workTypeCode: '',
    supervisionPassDate: ''
  }
}

Page({
  data: {
    canWrite: false,
    categories: ELEVATOR_CATEGORIES,
    categoryIndex: -1,
    workTypeLabels: WORK_TYPE_LABELS,
    workTypeIndex: -1,
    form: emptyForm(),
    // 使用单位 / 合同候选：接口无权限（安装员等）时降级为手工填写 ID
    unitLabels: [],
    unitIndex: -1,
    unitManual: false,
    manualUseUnitId: '',
    contractLabels: [],
    contractIndex: -1,
    contractManual: false,
    manualContractId: '',
    submitting: false,
    text: FILING_TEXT
  },

  onLoad() {
    if (!ensureLogin()) return
    const role = (getApp().globalData && getApp().globalData.role) || ''
    this.setData({ canWrite: FILING_WRITE_ROLES.indexOf(role) > -1 })
    this.loadUnits()
    this.loadContracts()
  },

  // GET /use-units：console:read 岗位可读（安装项目经理/文员/维保经理），安装员降级手工填写
  async loadUnits() {
    try {
      const list = await getUseUnits()
      this.setData({
        unitLabels: list.map(function (u) { return u.unitName }),
        unitManual: list.length === 0
      })
      this._units = list
    } catch (e) {
      this.setData({ unitManual: true, unitLabels: [] })
    }
  },

  // GET /contracts：当前仅平台层可读，其余岗位降级手工填写合同 ID（后端仍会校验合同生效与归属）
  async loadContracts() {
    try {
      const list = await getActiveContracts('')
      this.setData({ contractManual: list.length === 0 })
      this._contracts = list
    } catch (e) {
      this.setData({ contractManual: true })
      this._contracts = []
    }
  },

  onUnitChange(e) {
    const i = Number(e.detail.value)
    const unit = (this._units || [])[i] || {}
    const contracts = (this._contracts || []).filter(function (c) {
      return !c.useUnitId || c.useUnitId === unit.id
    })
    this.setData({
      unitIndex: i,
      contractIndex: -1,
      contractLabels: contracts.map(function (c) {
        return c.contractNo + (c.endDate ? '（至 ' + c.endDate + '）' : '')
      })
    })
    this._unitContracts = contracts
  },

  onContractChange(e) {
    this.setData({ contractIndex: Number(e.detail.value) })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!field) return
    const patch = {}
    patch['form.' + field] = e.detail.value
    this.setData(patch)
  },

  onManualInput(e) {
    const field = e.currentTarget.dataset.field
    const patch = {}
    patch[field] = (e.detail.value || '').trim()
    this.setData(patch)
  },

  onCategoryChange(e) {
    const i = Number(e.detail.value)
    this.setData({ categoryIndex: i, 'form.category': ELEVATOR_CATEGORIES[i] || '' })
  },

  onWorkTypeChange(e) {
    const i = Number(e.detail.value)
    this.setData({ workTypeIndex: i, 'form.workTypeCode': WORK_TYPE_CODES_LIST[i] || '' })
  },

  onDateChange(e) {
    this.setData({ 'form.supervisionPassDate': e.detail.value })
  },

  // 选中的使用单位 ID：候选未加载时取手工输入
  selectedUnitId() {
    if (this.data.unitManual) return this.data.manualUseUnitId
    const unit = (this._units || [])[this.data.unitIndex] || {}
    return unit.id || ''
  },

  // 选中的合同 ID：候选未加载时取手工输入
  selectedContractId() {
    if (this.data.contractManual) return this.data.manualContractId
    const list = this._unitContracts || this._contracts || []
    const c = list[this.data.contractIndex] || {}
    return c.id || ''
  },

  async onSubmit() {
    const d = this.data
    const useUnitId = this.selectedUnitId()
    if (!useUnitId) {
      wx.showToast({ title: FILING_TEXT.UNIT_REQUIRED, icon: 'none' })
      return
    }
    const contractId = this.selectedContractId()
    if (!contractId) {
      wx.showToast({ title: FILING_TEXT.CONTRACT_REQUIRED, icon: 'none' })
      return
    }
    const form = d.form
    if (form.ratedLoad && !/^\d+$/.test(String(form.ratedLoad).trim())) {
      wx.showToast({ title: FILING_TEXT.LOAD_INVALID, icon: 'none' })
      return
    }
    if (form.ratedSpeed && !/^\d+(\.\d+)?$/.test(String(form.ratedSpeed).trim())) {
      wx.showToast({ title: FILING_TEXT.SPEED_INVALID, icon: 'none' })
      return
    }
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    const payload = Object.assign({}, form, {
      useUnitId: useUnitId,
      contractId: contractId,
      projectName: form.projectName.trim(),
      elevatorCode: (form.elevatorCode || '').trim(),
      ratedLoad: form.ratedLoad ? Number(form.ratedLoad) : null,
      ratedSpeed: form.ratedSpeed ? Number(form.ratedSpeed) : null,
      initiatorId: user.id || '',
      initiatorName: user.name || ''
    })
    this.setData({ submitting: true })
    try {
      const order = await initFiling(payload)
      wx.showToast({ title: '建档单已发起', icon: 'success' })
      wx.redirectTo({ url: '/pages/filing/detail?id=' + order.id })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
