// 使用单位 · 维保记录查看（UI 文档 §3.3：签到/签退时间、检查项结果、照片九宫格、签名图、异常项说明）
const { getRecordDetail } = require('../../services/unit')
const { REPORT_STATUS_TEXT, CHECK_RESULT_TEXT, PROBLEM_CODES } = require('../../constants/index')

Page({
  data: {
    id: '',
    record: null,
    loading: true,
    reportStatusText: '',
    problemText: '',
    payloadRows: [], // 平台 2.6 上报快照键值对
    payloadExpanded: false
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
    this.fetchDetail()
  },

  async fetchDetail() {
    try {
      const record = await getRecordDetail(this.data.id)
      const items = (record.items || []).map((i) =>
        Object.assign({}, i, { resultText: CHECK_RESULT_TEXT[i.result] || i.result || '未填写' })
      )
      const problemText = (record.problemCodes || [])
        .map((c) => c + (PROBLEM_CODES[c] ? '（' + PROBLEM_CODES[c] + '）' : ''))
        .join('、')
      const payload = record.reportPayload || {}
      const payloadRows = Object.keys(payload).map((k) => ({
        k,
        v: Array.isArray(payload[k]) ? payload[k].join('、') : (payload[k] != null ? payload[k] : '')
      }))
      this.setData({
        record: Object.assign({}, record, { items }),
        reportStatusText: record.reportStatus
          ? REPORT_STATUS_TEXT[record.reportStatus] || record.reportStatus
          : '',
        problemText,
        payloadRows,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  togglePayload() {
    this.setData({ payloadExpanded: !this.data.payloadExpanded })
  },

  previewPhoto(e) {
    const current = e.currentTarget.dataset.src
    wx.previewImage({
      current,
      urls: (this.data.record && this.data.record.photos) || [current]
    })
  },

  goConfirm() {
    wx.navigateTo({ url: `/pages/unit/confirm?id=${this.data.id}` })
  }
})
