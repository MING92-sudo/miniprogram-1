// 外接建档接管查验（docs/04 A.10 / ExternalFilingService.submitTakeoverCheck）：
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
// 6 项逐项判 PASS/FAIL/NA + 备注；不合格项**不阻塞**建档，但必须选责任归属（原维保单位/使用单位/接管后我方）。
const { getFilingDetail, submitTakeoverCheck } = require('../../../services/filing')
const {
  FILING_STATUS,
  TAKEOVER_CHECK_ITEMS,
  HANDOVER_RESULTS,
  HANDOVER_RESULT_TEXT,
  TAKEOVER_RESPONSIBILITY_OPTIONS,
  TAKEOVER_RESPONSIBILITY_TEXT,
  FILING_WRITE_ROLES,
  FILING_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../../constants/index')
const { ensureLogin } = require('../../../utils/guard')

const RESP_LABELS = TAKEOVER_RESPONSIBILITY_OPTIONS.map(function (code) {
  return TAKEOVER_RESPONSIBILITY_TEXT[code]
})

definePage(ROUTES['/pkg-handover/pages/filing-inspect/index'], {
  data: {
    id: '',
    filing: null,
    loading: true,
    loadingText: LOADING_TEXT,
    locked: false,
    items: [],
    results: HANDOVER_RESULTS,
    resultText: HANDOVER_RESULT_TEXT,
    respLabels: RESP_LABELS,
    submitting: false,
    text: FILING_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const canWrite = FILING_WRITE_ROLES.indexOf(role) > -1
    const items = TAKEOVER_CHECK_ITEMS.map(function (i) {
      return { itemType: i.itemType, label: i.label, result: '', responsibility: '', respIndex: -1, remark: '' }
    })
    this.setData({ id: id, items: items })
    if (!id) {
      this.setData({ loading: false, locked: true })
      return
    }
    if (!canWrite) {
      this.setData({ locked: true })
    }
    this.fetch()
  },

  async fetch() {
    try {
      const f = await getFilingDetail(this.data.id)
      const statusLocked = f.status !== FILING_STATUS.TAKEOVER_CHECKING
      this.setData({ filing: f, loading: false, locked: this.data.locked || statusLocked })
    } catch (e) {
      this.setData({ loading: false, locked: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onResult(e) {
    const index = Number(e.currentTarget.dataset.index)
    const result = e.detail.value
    const patch = {}
    patch['items[' + index + '].result'] = result
    // 合格/不适用项不带责任归属（后端会清空，前端同步清避免脏数据误解）
    if (result !== 'FAIL') {
      patch['items[' + index + '].responsibility'] = ''
      patch['items[' + index + '].respIndex'] = -1
    }
    this.setData(patch)
  },

  onResponsibility(e) {
    const index = Number(e.currentTarget.dataset.index)
    const i = Number(e.detail.value)
    const patch = {}
    patch['items[' + index + '].responsibility'] = TAKEOVER_RESPONSIBILITY_OPTIONS[i] || ''
    patch['items[' + index + '].respIndex'] = i
    this.setData(patch)
  },

  onRemark(e) {
    const index = Number(e.currentTarget.dataset.index)
    const patch = {}
    patch['items[' + index + '].remark'] = e.detail.value
    this.setData(patch)
  },

  async onSubmit() {
    const d = this.data
    if (d.items.some(function (i) { return !i.result })) {
      wx.showToast({ title: FILING_TEXT.ITEMS_REQUIRED, icon: 'none' })
      return
    }
    const missingResp = d.items.some(function (i) {
      return i.result === 'FAIL' && !i.responsibility
    })
    if (missingResp) {
      wx.showToast({ title: FILING_TEXT.RESPONSIBILITY_REQUIRED, icon: 'none' })
      return
    }
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    const payload = {
      checkerId: user.id || '',
      checkerName: user.name || '',
      remark: '',
      items: d.items.map(function (i) {
        return {
          itemType: i.itemType,
          result: i.result,
          responsibility: i.result === 'FAIL' ? i.responsibility : null,
          remark: i.remark
        }
      })
    }
    this.setData({ submitting: true })
    try {
      await submitTakeoverCheck(d.id, payload)
      wx.showToast({ title: '查验已提交', icon: 'success' })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
