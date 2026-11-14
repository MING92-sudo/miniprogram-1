// 一键报修（M3，合并 fault/report + unit/report）：扫码回填电梯 + 故障描述（≥5 字）+ 故障等级 + 现场照片。
// CLIENT 一键报修（order:create）与员工代报修共用；电梯归属/越权由后端校验。
const { reportFault } = require('../../../services/fault')
const { getElevatorByCode } = require('../../../services/elevator')
const { uploadImage } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

const FAULT_TYPES = ['一般故障', '困人（紧急）']

definePage(ROUTES['/pages/order/report/index'], {
  data: { elevatorCode: '', elevator: null, faultType: FAULT_TYPES[0], faultTypes: FAULT_TYPES, desc: '', photos: [], submitting: false },
  onLoad(query) { if (query && query.elevatorId) this.setData({ elevatorCode: query.elevatorId }) },
  onBack() { nav.back() },
  onCodeInput(e) { this.setData({ elevatorCode: e.detail.value }) },
  onScan() { wx.scanCode({ onlyFromCamera: true, success: (res) => this.backfill(res.result) }) },
  backfill(code) {
    getElevatorByCode(String(code).trim())
      .then((elevator) => this.setData({ elevator, elevatorCode: elevator.elevatorCode }))
      .catch((e) => wx.showToast({ title: (e && e.message) || '电梯信息获取失败', icon: 'none' }))
  },
  onDescInput(e) { this.setData({ desc: e.detail.value }) },
  onTypeChange(e) { this.setData({ faultType: FAULT_TYPES[Number(e.detail.value)] }) },
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
    if (!this.data.elevatorCode) return wx.showToast({ title: '请输入电梯编号', icon: 'none' })
    if (!this.data.desc || this.data.desc.trim().length < 5) return wx.showToast({ title: '故障描述至少 5 个字', icon: 'none' })
    this.setData({ submitting: true })
    reportFault({ elevatorCode: this.data.elevatorCode, faultType: this.data.faultType, desc: this.data.desc.trim(), photos: this.data.photos })
      .then((fault) => { wx.showToast({ title: '报修已提交', icon: 'success' }); setTimeout(() => nav.open('/pages/order/detail/index?id=' + fault.id), 800) })
      .catch((e) => wx.showToast({ title: (e && e.message) || '上报失败', icon: 'none' }))
      .finally(() => this.setData({ submitting: false }))
  }
})