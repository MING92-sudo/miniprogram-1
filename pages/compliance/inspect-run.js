// 自行检查执行（docs/01 §3.17：复用年度维保检查项，独立记录类型，不上报平台）
const { getInspectTemplate, createInspect } = require('../../services/compliance')

Page({
  data: {
    elevatorId: '',
    elevatorName: '',
    items: [], // { itemCode, name, requirement, result: ''|NORMAL|ABNORMAL, abnormalDesc }
    total: 0,
    marked: 0,
    abnormal: 0,
    inspectorSign: '',
    reviewerSign: '',
    submitting: false
  },

  onLoad(query) {
    this.setData({
      elevatorId: query.elevatorId || '',
      elevatorName: decodeURIComponent(query.elevatorName || '')
    })
    this.fetchTemplate()
  },

  async fetchTemplate() {
    try {
      const data = await getInspectTemplate(this.data.elevatorId)
      const items = (data.items || []).map(function (it) {
        return {
          itemCode: it.itemCode,
          name: it.name,
          requirement: it.requirement,
          result: '',
          abnormalDesc: ''
        }
      })
      this.setData({ items: items, total: items.length })
      this.refreshStats()
    } catch (e) {
      wx.showToast({ title: e.message || '模板加载失败', icon: 'none' })
    }
  },

  refreshStats() {
    const items = this.data.items
    this.setData({
      marked: items.filter(function (i) { return i.result }).length,
      abnormal: items.filter(function (i) { return i.result === 'ABNORMAL' }).length
    })
  },

  markResult(e) {
    const { index, result } = e.currentTarget.dataset
    const key = 'items[' + index + '].result'
    const patch = {}
    patch[key] = this.data.items[index].result === result ? '' : result
    this.setData(patch)
    this.refreshStats()
  },

  onDescInput(e) {
    const patch = {}
    patch['items[' + e.currentTarget.dataset.index + '].abnormalDesc'] = e.detail.value
    this.setData(patch)
  },

  markAllNormal() {
    wx.showModal({
      title: '全部合格',
      content: '将把全部检查项标记为合格，已单独标记的不合格项会被覆盖。确认？',
      success: (res) => {
        if (!res.confirm) return
        const patch = {}
        this.data.items.forEach(function (item, i) {
          patch['items[' + i + '].result'] = 'NORMAL'
        })
        this.setData(patch)
        this.refreshStats()
      }
    })
  },

  // 签字：检查人员 / 审核人员（复用手写签名板）
  goSignature(e) {
    this._sigTarget = e.currentTarget.dataset.field
    wx.navigateTo({
      url: `/pages/common/signature?from=inspect&elevatorId=${this.data.elevatorId}`
    })
  },

  // 签名板回传（EventChannel + 直接方法双通道，见 signature.js）
  onSignatureReady(data) {
    if (this._sigTarget) this.setData({ [this._sigTarget]: data.path })
  },

  async onSubmit() {
    const items = this.data.items
    const unmarked = items.filter(function (i) { return !i.result }).length
    if (unmarked) return wx.showToast({ title: '尚有 ' + unmarked + ' 项未填写结果', icon: 'none' })
    if (!this.data.inspectorSign) return wx.showToast({ title: '请完成检查人员签字', icon: 'none' })
    if (!this.data.reviewerSign) return wx.showToast({ title: '请完成审核人员签字', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await createInspect({
        elevatorId: this.data.elevatorId,
        items: items,
        inspectorSign: this.data.inspectorSign,
        reviewerSign: this.data.reviewerSign
      })
      wx.showToast({ title: '已生成检查记录', icon: 'success' })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || '提交失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
