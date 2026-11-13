// 使用单位报修（W3，docs/04 A.11 POST /unit/faults）：只能从本单位电梯选（越权后端 1403），
// 后端复用急修链路建单并派给该梯责任人；照片上传走 services/unit.uploadUnitPhoto（门户令牌）。
const { getUnitElevators, reportUnitFault, uploadUnitPhoto, isUnitLoggedIn } = require('../../services/unit')
const { UNIT_PORTAL_TEXT, UNIT_FAULT_TYPES, TOAST_TEXT } = require('../../constants/index')

const MAX_PHOTOS = 3

Page({
  data: {
    elevators: [],
    elevatorLabels: [],
    elevatorIndex: -1,
    faultTypes: UNIT_FAULT_TYPES,
    faultType: UNIT_FAULT_TYPES[0],
    desc: '',
    siteDesc: '',
    photos: [],
    loading: true,
    submitting: false,
    text: UNIT_PORTAL_TEXT
  },

  onLoad() {
    if (!isUnitLoggedIn()) {
      wx.reLaunch({ url: '/pages/login' })
      return
    }
    this.fetchElevators()
  },

  async fetchElevators() {
    try {
      const list = await getUnitElevators()
      this.setData({
        elevators: list || [],
        elevatorLabels: (list || []).map(function (el) {
          return (el.elevatorName || el.elevatorCode || '未命名') + '（' + (el.elevatorCode || '-') + '）'
        }),
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onElevatorChange(e) {
    this.setData({ elevatorIndex: Number(e.detail.value) })
  },

  onTypeChange(e) {
    this.setData({ faultType: e.detail.value })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (!field) return
    const patch = {}
    patch[field] = e.detail.value
    this.setData(patch)
  },

  onAddPhoto() {
    const remain = MAX_PHOTOS - this.data.photos.length
    if (remain <= 0) return
    wx.chooseMedia({
      count: remain,
      mediaType: ['image'],
      success: async (res) => {
        wx.showLoading({ title: '上传中' })
        try {
          const urls = []
          for (const f of res.tempFiles) {
            const up = await uploadUnitPhoto(f.tempFilePath)
            urls.push(up.url || up.fileId)
          }
          this.setData({ photos: this.data.photos.concat(urls) })
        } catch (e) {
          wx.showToast({ title: e.message || '照片上传失败', icon: 'none' })
        }
        wx.hideLoading()
      }
    })
  },

  onRemovePhoto(e) {
    const i = Number(e.currentTarget.dataset.index)
    this.setData({
      photos: this.data.photos.filter(function (_, n) { return n !== i })
    })
  },

  onPreviewPhoto(e) {
    wx.previewImage({ urls: this.data.photos, current: e.currentTarget.dataset.url })
  },

  async onSubmit() {
    const d = this.data
    const elevator = d.elevators[d.elevatorIndex] || null
    if (!elevator) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.ELEVATOR_REQUIRED, icon: 'none' })
      return
    }
    if (!d.desc.trim()) {
      wx.showToast({ title: UNIT_PORTAL_TEXT.DESC_REQUIRED, icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await reportUnitFault({
        elevatorCode: elevator.elevatorCode,
        desc: d.desc.trim(),
        faultType: d.faultType,
        siteDesc: d.siteDesc.trim(),
        photos: d.photos
      })
      wx.showToast({ title: UNIT_PORTAL_TEXT.REPORT_SUCCESS, icon: 'success' })
      wx.redirectTo({ url: '/pages/unit/faults' })
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
