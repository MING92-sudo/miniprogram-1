// 作业清单：表格形式（维保内容/维保要求/结果/备注），UI 参照无纸化维保截屏
const { getChecklist, runItemThisTime } = require('../../services/order')
const { CHECK_RESULT_TEXT } = require('../../constants/index')

const RESULT_TEXT = CHECK_RESULT_TEXT // NORMAL/ABNORMAL/NA（docs/04 A.2 枚举）

Page({
  data: {
    orderId: '',
    items: [],
    photos: [], // 全部已拍照片汇总（含来源检查项）
    doneCount: 0,
    total: 0,
    requiredTotal: 0,
    progress: 0,
    loading: true
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
  },

  // 从检查项填写页返回后自动刷新
  onShow() {
    if (this.data.orderId) {
      this.fetchChecklist()
    }
  },

  async fetchChecklist() {
    try {
      const checklist = await getChecklist(this.data.orderId)
      const items = ((checklist && checklist.items) || []).map(function (i) {
        const photos = i.photos || []
        // 备注列：异常描述 > 说明书结论 > 读数 > 跳过原因 > 照片数
        let remark = i.abnormalDesc || i.valueText ||
          (i.value != null ? '读数 ' + i.value + (i.valueUnit || '') : '') ||
          i.skipReason || (photos.length ? '照片 ' + photos.length + ' 张' : '')
        return Object.assign({}, i, {
          resultText: i.result ? RESULT_TEXT[i.result] : '',
          remark: remark,
          photoCount: photos.length
        })
      })
      const photos = []
      items.forEach(function (i) {
        (i.photos || []).forEach(function (p) {
          photos.push({ src: p, itemName: i.name })
        })
      })
      const doneCount = items.filter(function (i) { return i.result }).length
      // 结单口径与后端 checkout 一致：周期性条目（notInThisRun）不计入必填
      const requiredTotal = items.filter(function (i) { return !i.notInThisRun }).length
      this.setData({
        items: items,
        photos: photos,
        doneCount: doneCount,
        total: items.length,
        requiredTotal: requiredTotal,
        progress: items.length ? Math.round((doneCount / items.length) * 100) : 0,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  // 进入检查项填写页
  goItemForm(e) {
    const itemId = e.currentTarget.dataset.id
    wx.navigateTo({
      url: `/pages/order/item-form?orderId=${this.data.orderId}&itemId=${itemId}`
    })
  },

  // 悬浮锚点导航：内容（表格顶部）/ 结论（签退）/ 照片（汇总）
  goSection(e) {
    wx.pageScrollTo({ selector: '#' + e.currentTarget.dataset.target, offsetTop: -10, duration: 300 })
  },

  // 照片汇总：点击全屏预览（可左右滑动查看本单全部照片）
  previewPhoto(e) {
    const current = e.currentTarget.dataset.src
    wx.previewImage({
      current: current,
      urls: this.data.photos.map(function (p) { return p.src })
    })
  },

  // 全部完成后进入签退
  goCheckout() {
    // 口径对齐 mock/server.js checkout：只校验非周期项（notInThisRun 不拦截）
    const missing = this.data.items.filter(function (i) {
      return !i.notInThisRun && !i.result
    }).length
    if (missing > 0) {
      wx.showToast({ title: `还有 ${missing} 项未填写`, icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/order/checkout?orderId=${this.data.orderId}` })
  },

  // 周期性条目：本次仍要执行（docs/03 §6.1）
  async onRunThisTime(e) {
    const itemId = e.currentTarget.dataset.id
    try {
      await runItemThisTime(this.data.orderId, itemId)
      wx.showToast({ title: '已加入本次执行', icon: 'success' })
      this.fetchChecklist()
    } catch (err) {
      wx.showToast({ title: err.message || '操作失败', icon: 'none' })
    }
  }
})
