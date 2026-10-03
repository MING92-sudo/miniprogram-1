// 维保记录预览页（docs/03 §六 信息完整性清单）：与使用单位确认同源，属本地留痕展示层。
// 仅展示实际执行的检查项（周期项"本次无需执行"不进入本次记录），并汇总"执行 N 项（共 M 项）"。
const { getRecord } = require('../../services/unit')
const { CHECK_RESULT_TEXT } = require('../../constants/index')

Page({
  data: {
    loading: true,
    record: null,
    context: null,
    items: [],
    itemTotal: 0,
    photoList: [],
    problemText: 'S0',
    confirmed: false
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
      const all = r.items || []
      // 周期项"本次无需执行"不进入本次维保记录（docs/03 V2.0 §8.3）
      const executed = all.filter(function (i) { return i.notInThisRun !== true })
      const ctx = r.context || {}
      const photoList = []
      executed.forEach(function (i) {
        (i.photos || []).forEach(function (url) {
          photoList.push({ url: url, abnormal: i.result === 'ABNORMAL', itemName: i.name })
        })
      })
      that.setData({
        loading: false,
        record: r,
        context: ctx,
        items: executed.map(function (i) {
          return Object.assign({}, i, {
            resultText: i.result ? (CHECK_RESULT_TEXT[i.result] || i.result) : '-',
            remark: i.abnormalDesc || i.valueText ||
              (i.value != null ? '读数 ' + i.value + (i.valueUnit || '') : '') || i.skipReason || ''
          })
        }),
        itemTotal: ctx.itemTotal || all.length,
        // 照片墙：检查项照片，异常项红色描边（签到水印自拍回显依赖 P4 照片归档 /files/{id} 回源，暂未纳入）
        photoList: photoList.length ? photoList : (r.photos || []).map(function (url) {
          return { url: url, abnormal: false, itemName: '' }
        }),
        problemText: (r.problemCodes && r.problemCodes.length) ? r.problemCodes.join('、') : 'S0',
        confirmed: r.confirmStatus === 'CONFIRMED'
      })
    }).catch(function (e) {
      that.setData({ loading: false })
      wx.showToast({ title: (e && e.message) || '记录加载失败', icon: 'none' })
    })
  },

  previewPhoto(e) {
    const urls = this.data.photoList.map(function (p) { return p.url })
    wx.previewImage({ current: e.currentTarget.dataset.url, urls: urls })
  },

  // 分享确认链接（token 免登录）：复制小程序内路径，交由使用单位安全管理员打开签字（docs/03 §六 操作）
  copySignLink() {
    const r = this.data.record
    if (!r || !r.shareToken) return wx.showToast({ title: '暂无确认链接', icon: 'none' })
    const link = '/pages/unit/sign?rid=' + r.id + '&token=' + r.shareToken
    wx.setClipboardData({
      data: link,
      success: function () {
        wx.showToast({ title: '确认链接已复制', icon: 'none' })
      }
    })
  },

  goSign() {
    const r = this.data.record
    if (!r || !r.shareToken) return
    wx.navigateTo({ url: '/pages/unit/sign?rid=' + r.id + '&token=' + r.shareToken })
  },

  goBack() {
    wx.navigateBack({
      fail: function () {
        wx.switchTab({ url: '/pages/order/list' })
      }
    })
  }
})
