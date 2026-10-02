// 急修单登记：扫码回填电梯信息 + 故障描述 + 故障位置照片（docs/01 §3.9.1）
const { reportFault } = require('../../services/fault')
const { getElevatorByCode } = require('../../services/elevator')
const { uploadImage } = require('../../services/upload')

Page({
  data: {
    elevatorCode: '',
    elevator: null,
    desc: '',
    photos: [],
    submitting: false
  },

  onCodeInput(e) {
    this.setData({ elevatorCode: e.detail.value })
  },

  // 扫电梯二维码回填基本信息（docs/04：二维码内容 = 纯 elevatorCode）
  onScan() {
    wx.scanCode({
      onlyFromCamera: true,
      success: (res) => this.backfill(res.result)
    })
  },

  async backfill(code) {
    try {
      const elevator = await getElevatorByCode(code.trim())
      this.setData({ elevator, elevatorCode: elevator.elevatorCode })
    } catch (e) {
      wx.showToast({ title: e.message || '电梯信息获取失败', icon: 'none' })
    }
  },

  onDescInput(e) {
    this.setData({ desc: e.detail.value })
  },

  // 故障位置照片（最多 3 张）
  onAddPhoto() {
    wx.chooseMedia({
      count: 3 - this.data.photos.length,
      mediaType: ['image'],
      success: async (res) => {
        wx.showLoading({ title: '上传中' })
        try {
          const urls = []
          for (const f of res.tempFiles) {
            const up = await uploadImage(f.tempFilePath)
            urls.push(up.url)
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
    this.setData({ photos: this.data.photos.filter((_, n) => n !== i) })
  },

  onPreviewPhoto(e) {
    wx.previewImage({ urls: this.data.photos, current: e.currentTarget.dataset.url })
  },

  async onSubmit() {
    if (!this.data.elevatorCode) return wx.showToast({ title: '请输入电梯编号', icon: 'none' })
    if (!this.data.desc) return wx.showToast({ title: '请填写故障描述', icon: 'none' })
    this.setData({ submitting: true })
    try {
      const fault = await reportFault({
        elevatorCode: this.data.elevatorCode,
        desc: this.data.desc,
        photos: this.data.photos
      })
      wx.showToast({ title: '急修单已登记', icon: 'success' })
      // 跳转详情页可继续跟踪闭环
      wx.redirectTo({ url: `/pages/fault/detail?id=${fault.id}` })
    } catch (e) {
      wx.showToast({ title: e.message || '上报失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
