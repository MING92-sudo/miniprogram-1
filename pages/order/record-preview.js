// 维保记录预览页（docs/03 V2.0 §5.3：记录单与使用单位确认同源，本地留痕展示层）
const { getRecord } = require('../../services/unit')

Page({
  data: {
    loading: true,
    record: null,
    items: [],
    photos: [],
    problemText: 'S0'
  },

  onLoad(options) {
    this.recordId = options.recordId
  },

  onShow() {
    this.load()
  },

  load() {
    if (!this.recordId) return
    const that = this
    that.setData({ loading: true })
    getRecord(this.recordId).then(function (r) {
      that.setData({
        loading: false,
        record: r,
        items: r.items || [],
        photos: r.photos || [],
        problemText: (r.problemCodes && r.problemCodes.length) ? r.problemCodes.join('、') : 'S0'
      })
    }).catch(function () {
      that.setData({ loading: false })
    })
  },

  previewPhoto(e) {
    wx.previewImage({ current: e.currentTarget.dataset.url, urls: this.data.photos })
  }
})
