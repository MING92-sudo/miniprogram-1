// 客户一键报修与维保主管代报修共用：客户主体走 POST /unit/faults（客户令牌白名单仅放 /unit/**，且电梯属本单位校验在服务端，越权 1403），员工走 POST /faults。
const { reportFault } = require('../../../services/fault')
const { reportUnitFault, getUnitElevators } = require('../../../services/unit')
const { getElevatorByCode } = require('../../../services/elevator')
const { uploadImage } = require('../../../services/upload')
const { FAULT_TYPE_OPTIONS, FAULT_TYPE_TRAPPED } = require('../../../constants/index')
const { isCustomerPrincipal } = require('../../../utils/auth')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const FAULT_TYPES = FAULT_TYPE_OPTIONS

definePage(ROUTES['/pages/order/report/index'], {
  data: {
    elevatorCode: '', elevator: null, faultType: FAULT_TYPES[0], faultTypes: FAULT_TYPES,
    trappedType: FAULT_TYPE_TRAPPED,
    isCustomer: false, unitElevators: [], elevatorOptions: [], elevatorIndex: -1,
    elevatorsLoading: false, elevatorsError: '',
    desc: '', trappedCount: '', photos: [], submitting: false
  },
  onLoad(query) {
    const isCustomer = isCustomerPrincipal()
    const requestedCode = query && query.elevatorId ? String(query.elevatorId) : ''
    this.setData({ isCustomer, elevatorCode: requestedCode })
    if (isCustomer) this.loadUnitElevators(requestedCode)
  },
  loadUnitElevators(requestedCode) {
    this.setData({ elevatorsLoading: true, elevatorsError: '' })
    getUnitElevators()
      .then((list) => {
        const elevators = Array.isArray(list) ? list : []
        const options = elevators.map((el) => {
          const name = el.elevatorName || el.insideNumber || el.elevatorCode
          return name + ' · ' + el.elevatorCode + (el.projectName ? ' · ' + el.projectName : '')
        })
        const preferredCode = this.data.elevatorCode || requestedCode
        const index = elevators.findIndex((el) => el.elevatorCode === preferredCode || el.id === preferredCode)
        const selected = index >= 0 ? elevators[index] : null
        this.setData({
          unitElevators: elevators, elevatorOptions: options, elevatorIndex: index,
          elevator: selected, elevatorCode: selected ? selected.elevatorCode : '', elevatorsLoading: false
        })
      })
      .catch((e) => this.setData({
        elevatorsLoading: false, elevatorsError: (e && e.message) || '本单位电梯加载失败'
      }))
  },
  onElevatorChange(e) {
    const index = Number(e.detail.value)
    const elevator = this.data.unitElevators[index]
    if (!elevator) return
    this.setData({ elevator, elevatorCode: elevator.elevatorCode, elevatorIndex: index })
  },
  onBack() { nav.back() },
  onCodeInput(e) { this.setData({ elevatorCode: e.detail.value, elevator: null }) },
  onScan() { wx.scanCode({ onlyFromCamera: true, success: (res) => this.backfill(res.result) }) },
  backfill(code) {
    getElevatorByCode(String(code).trim())
      .then((elevator) => {
        const index = this.data.unitElevators.findIndex((el) => el.elevatorCode === elevator.elevatorCode)
        this.setData({ elevator, elevatorCode: elevator.elevatorCode, elevatorIndex: index })
      })
      .catch((e) => wx.showToast({ title: (e && e.message) || '电梯信息获取失败', icon: 'none' }))
  },
  onDescInput(e) { this.setData({ desc: e.detail.value }) },
  onTypeChange(e) {
    const faultType = FAULT_TYPES[Number(e.detail.value)]
    const patch = { faultType }
    if (faultType !== this.data.trappedType) patch.trappedCount = ''
    this.setData(patch)
  },
  onTrappedInput(e) { this.setData({ trappedCount: e.detail.value }) },
  onAddPhoto() {
    wx.chooseMedia({
      count: 3 - this.data.photos.length, mediaType: ['image'],
      success: (res) => {
        wx.showLoading({ title: '上传中' })
        const files = res.tempFiles
        const next = (i, urls) => {
          if (i >= files.length) { wx.hideLoading(); this.setData({ photos: this.data.photos.concat(urls) }); return }
          uploadImage(files[i].tempFilePath).then((up) => next(i + 1, urls.concat(up.url)))
            .catch((e) => { wx.hideLoading(); wx.showToast({ title: (e && e.message) || '照片上传失败', icon: 'none' }) })
        }
        next(0, [])
      }
    })
  },
  onRemovePhoto(e) { const i = Number(e.currentTarget.dataset.index); this.setData({ photos: this.data.photos.filter((_, n) => n !== i) }) },
  onPreviewPhoto(e) { wx.previewImage({ urls: this.data.photos, current: e.currentTarget.dataset.url }) },
  onSubmit() {
    if (!this.data.elevatorCode) return wx.showToast({ title: this.data.isCustomer ? '请选择本单位电梯' : '请输入电梯编号', icon: 'none' })
    if (!this.data.desc || this.data.desc.trim().length < 5) return wx.showToast({ title: '故障描述至少 5 个字', icon: 'none' })
    const trapped = Number(this.data.trappedCount) || 0
    if (this.data.faultType === FAULT_TYPE_TRAPPED && trapped < 1) {
      return wx.showToast({ title: '困人急修请填写被困人数', icon: 'none' })
    }
    this.setData({ submitting: true })
    const payload = { elevatorCode: this.data.elevatorCode, faultType: this.data.faultType, desc: this.data.desc.trim(), trappedCount: trapped, photos: this.data.photos }
    // 客户主体走客户门户链路（/unit/faults，服务端按本单位锚点收敛）；员工维持代报修链路（/faults）
    const submit = isCustomerPrincipal() ? reportUnitFault(payload) : reportFault(payload)
    submit
      .then((fault) => {
        wx.showToast({ title: '报修已提交', icon: 'success' })
        // 客户无权打开员工急修详情页（GET /faults/* 不在客户白名单），提交成功回上一页
        if (isCustomerPrincipal()) { setTimeout(() => nav.back(), 800); return }
        setTimeout(() => nav.open('/pages/order/detail/index?id=' + fault.id), 800)
      })
      .catch((e) => wx.showToast({ title: (e && e.message) || '上报失败', icon: 'none' }))
      .finally(() => this.setData({ submitting: false }))
  }
})