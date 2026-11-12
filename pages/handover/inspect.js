// 移交单现场查验（docs/04 A.9 / HandoverService.submitInspect）：6 类固定项逐项判 PASS/FAIL/NA + 备注，
// 安全装置/制动器任一不合格由后端强制转「整改复验中」；不合格项整改前后照片在整改页上传。
const { getHandoverDetail, submitHandoverInspect } = require('../../services/handover')
const {
  ROLE,
  HANDOVER_STATUS,
  HANDOVER_CHECK_ITEMS,
  HANDOVER_RESULTS,
  HANDOVER_RESULT_TEXT,
  HANDOVER_TEXT,
  TOAST_TEXT,
  LOADING_TEXT
} = require('../../constants/index')
const { ensureLogin } = require('../../utils/guard')

// 现场查验岗位（HandoverService.NODE_ROLES：SITE_INSPECT = WORKER/LEADER/ADMIN/SYS_ADMIN）
const INSPECT_ROLES = [ROLE.WORKER, ROLE.LEADER, ROLE.ADMIN, ROLE.SYS_ADMIN]

// 不合格项缓存键：详情页/整改页据此预选需整改项（GET 详情不返回查验明细）
function failItemsKey(id) {
  return 'handover_fail_' + id
}

Page({
  data: {
    id: '',
    handover: null,
    loading: true,
    loadingText: LOADING_TEXT,
    canInspect: false,
    locked: false,
    items: [],
    results: HANDOVER_RESULTS,
    resultText: HANDOVER_RESULT_TEXT,
    submitting: false,
    text: HANDOVER_TEXT
  },

  onLoad(query) {
    if (!ensureLogin()) return
    const id = (query && query.id) || ''
    const role = (getApp().globalData && getApp().globalData.role) || ''
    const items = HANDOVER_CHECK_ITEMS.map(function (i) {
      return { itemType: i.itemType, label: i.label, rectifyHint: i.rectify, result: '', remark: '' }
    })
    this.setData({ id: id, items: items, canInspect: INSPECT_ROLES.indexOf(role) > -1 })
    if (!id) {
      this.setData({ loading: false, locked: true })
      return
    }
    this.fetch()
  },

  async fetch() {
    try {
      const h = await getHandoverDetail(this.data.id)
      const locked = h.status !== HANDOVER_STATUS.INSPECTING || !this.data.canInspect
      this.setData({ handover: h, loading: false, locked: locked })
    } catch (e) {
      this.setData({ loading: false, locked: true })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onResult(e) {
    const index = Number(e.currentTarget.dataset.index)
    const patch = {}
    patch['items[' + index + '].result'] = e.detail.value
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
    const pending = d.items.some(function (i) { return !i.result })
    if (pending) {
      wx.showToast({ title: HANDOVER_TEXT.ITEMS_REQUIRED, icon: 'none' })
      return
    }
    const user = (getApp().globalData && getApp().globalData.userInfo) || {}
    const payload = {
      checkerId: user.id || '',
      checkerName: user.name || '',
      remark: '',
      items: d.items.map(function (i) {
        return { itemType: i.itemType, result: i.result, remark: i.remark }
      })
    }
    this.setData({ submitting: true })
    try {
      const order = await submitHandoverInspect(d.id, payload)
      // 记录不合格项，供整改页预选（详情接口不下发查验明细）
      const fails = d.items
        .filter(function (i) { return i.result === 'FAIL' })
        .map(function (i) { return i.itemType })
      wx.setStorageSync(failItemsKey(d.id), fails)
      wx.showToast({
        title: order.status === HANDOVER_STATUS.RECTIFYING ? '需整改后复验' : '查验已提交',
        icon: 'none'
      })
      wx.navigateBack()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
