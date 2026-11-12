// 发起移交单（安装部，docs/04 A.9）：电梯信息快照 + 监督检验合格日，
// 提交后进入 DOC_SUBMITTING，3 必传资料在详情页上传（缺一禁止提交资料，HandoverService.submitDocs）
const { initHandover } = require('../../services/handover')
const {
  ELEVATOR_CATEGORIES,
  WORK_TYPE_CODES,
  HANDOVER_WRITE_ROLES,
  HANDOVER_TEXT,
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
    submitting: false,
    text: HANDOVER_TEXT
  },

  onLoad() {
    if (!ensureLogin()) return
    const app = getApp()
    const role = (app.globalData && app.globalData.role) || ''
    this.setData({ canWrite: HANDOVER_WRITE_ROLES.indexOf(role) > -1 })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!field) return
    const patch = {}
    patch['form.' + field] = e.detail.value
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

  async onSubmit() {
    const form = this.data.form
    if (!form.projectName.trim()) {
      wx.showToast({ title: HANDOVER_TEXT.PROJECT_REQUIRED, icon: 'none' })
      return
    }
    if (form.ratedLoad && !/^\d+$/.test(String(form.ratedLoad).trim())) {
      wx.showToast({ title: HANDOVER_TEXT.LOAD_INVALID, icon: 'none' })
      return
    }
    if (form.ratedSpeed && !/^\d+(\.\d+)?$/.test(String(form.ratedSpeed).trim())) {
      wx.showToast({ title: HANDOVER_TEXT.SPEED_INVALID, icon: 'none' })
      return
    }
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    const payload = Object.assign({}, form, {
      projectName: form.projectName.trim(),
      ratedLoad: form.ratedLoad ? Number(form.ratedLoad) : null,
      ratedSpeed: form.ratedSpeed ? Number(form.ratedSpeed) : null,
      initiatorId: user.id || '',
      initiatorName: user.name || ''
    })
    this.setData({ submitting: true })
    try {
      const order = await initHandover(payload)
      wx.showToast({ title: '移交单已发起', icon: 'success' })
      wx.redirectTo({ url: '/pages/handover/detail?id=' + order.id })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
