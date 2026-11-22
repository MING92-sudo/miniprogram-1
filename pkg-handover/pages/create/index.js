const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 三份资料与电梯信息同页提交，齐全后进入 PENDING_INST_REVIEW；存量单据仍走详情页 submitDocs 补传。
const { initHandover, getHandoverOptions } = require('../../../services/handover')
const { uploadImage } = require('../../../services/upload')
const {
  ELEVATOR_CATEGORIES,
  WORK_TYPE_CODES,
  HANDOVER_TEXT,
  HANDOVER_CREATE_TEXT,
  HANDOVER_FLOW,
  TOAST_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')

const WORK_TYPE_CODES_LIST = Object.keys(WORK_TYPE_CODES)
const REQUIRED_DOCS = [
  { field: 'supervisionReportFileId', label: '电梯监督检验报告', fileId: '' },
  { field: 'qualityCertificateFileId', label: '安装质量证明文件', fileId: '' },
  { field: 'maintenanceManualFileId', label: '维护使用说明书', fileId: '' }
]
const WORK_TYPE_LABELS = WORK_TYPE_CODES_LIST.map(function (c) {
  return c + ' ' + WORK_TYPE_CODES[c]
})

function emptyForm() {
  return {
    projectName: '',
    useUnitId: '',
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
    factoryNumber: '',
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

definePage(ROUTES['/pkg-handover/pages/create/index'], {
  data: {
    canWrite: false,
    categories: ELEVATOR_CATEGORIES,
    categoryIndex: -1,
    workTypeLabels: WORK_TYPE_LABELS,
    workTypeIndex: -1,
    form: emptyForm(),
    useUnitOptions: [],
    useUnitIndex: -1,
    docs: REQUIRED_DOCS.map((d) => Object.assign({}, d)),
    uploadingField: '',
    submitting: false,
    text: HANDOVER_TEXT, ct: HANDOVER_CREATE_TEXT,
    flow: HANDOVER_FLOW.map((label, i) => ({ label, state: i === 0 ? 'active' : '' }))
  },

  async onLoad() {
    if (!ensureLogin()) return
    const app = getApp()
    // 按权限矩阵门控（handover:create=INST_PM/INST_CLERK/系统管理员），与后端 MultiRoleGate 同口径
    const perms = (app.globalData && app.globalData.perms) || []
    this.setData({ canWrite: perms.indexOf('handover:create') > -1 })
    try {
      const options = await getHandoverOptions()
      this.setData({ useUnitOptions: (options && options.useUnits) || [] })
    } catch (e) {
      wx.showToast({ title: e.message || '使用单位档案加载失败', icon: 'none' })
    }
  },

  onUseUnitChange(e) {
    const index = Number(e.detail.value)
    const unit = this.data.useUnitOptions[index]
    this.setData({ useUnitIndex: index, 'form.useUnitId': unit ? unit.id : '' })
  },

  onChooseDoc(e) {
    const field = e.currentTarget.dataset.field
    if (!field || this.data.uploadingField) return
    wx.showActionSheet({
      itemList: ['拍照或相册选择', '聊天记录中的文件'],
      success: (res) => {
        if (res.tapIndex === 0) {
          wx.chooseMedia({
            count: 1, mediaType: ['image'],
            success: (media) => this.uploadDoc(field, media.tempFiles[0].tempFilePath)
          })
        } else {
          wx.chooseMessageFile({
            count: 1, type: 'file', extension: ['pdf', 'jpg', 'jpeg', 'png'],
            success: (file) => this.uploadDoc(field, file.tempFiles[0].path)
          })
        }
      }
    })
  },

  async uploadDoc(field, filePath) {
    const index = this.data.docs.findIndex((d) => d.field === field)
    if (index < 0) return
    this.setData({ uploadingField: field })
    wx.showLoading({ title: '上传中' })
    try {
      const up = await uploadImage(filePath)
      const docs = this.data.docs.slice()
      docs[index] = Object.assign({}, docs[index], { fileId: up.fileId })
      this.setData({ docs })
    } catch (e) {
      wx.showToast({ title: e.message || '资料上传失败', icon: 'none' })
    } finally {
      wx.hideLoading()
      this.setData({ uploadingField: '' })
    }
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
    if (this.data.submitting) return
    const form = this.data.form
    if (!form.projectName.trim()) {
      wx.showToast({ title: HANDOVER_TEXT.PROJECT_REQUIRED, icon: 'none' })
      return
    }
    if (!form.useUnitId) {
      wx.showToast({ title: HANDOVER_TEXT.USE_UNIT_REQUIRED, icon: 'none' })
      return
    }
    if (!form.factoryNumber.trim()) {
      wx.showToast({ title: HANDOVER_TEXT.FACTORY_REQUIRED, icon: 'none' })
      return
    }
    if (!form.insideNumber.trim()) {
      wx.showToast({ title: HANDOVER_TEXT.INSIDE_NUMBER_REQUIRED, icon: 'none' })
      return
    }
    if (this.data.docs.some((d) => !d.fileId)) {
      wx.showToast({ title: HANDOVER_TEXT.DOCS_REQUIRED, icon: 'none' })
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
      supervisionReportFileId: this.data.docs.find((d) => d.field === 'supervisionReportFileId').fileId,
      maintenanceManualFileId: this.data.docs.find((d) => d.field === 'maintenanceManualFileId').fileId,
      qualityCertificateFileId: this.data.docs.find((d) => d.field === 'qualityCertificateFileId').fileId,
      initiatorId: user.id || '',
      initiatorName: user.name || ''
    })
    this.setData({ submitting: true })
    try {
      const order = await initHandover(payload)
      wx.showToast({ title: '移交单已发起', icon: 'success' })
      wx.redirectTo({ url: '/pkg-handover/pages/detail/index?id=' + order.id })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
