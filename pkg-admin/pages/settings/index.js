// 系统配置（M6，平台管理员 config:manage）：SLA/保养周期/签字模板/复核开关/脱敏开关；取值约束见管理端，保存留痕。
const admin = require('../../../services/admin')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-admin/pages/settings/index'], {
  data: { items: [], loading: true, error: '' },
  onShow() { this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    admin.getSettings().then((res) => this.setData({ items: Array.isArray(res) ? res : ((res && res.list) || []), loading: false }))
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  onEdit(e) {
    const item = e.currentTarget.dataset.item
    wx.showModal({
      title: item.remark || item.key, editable: true, placeholderText: '请输入配置值', content: String(item.value || ''),
      success: (res) => {
        if (!res.confirm) return
        const v = (res.content || '').trim()
        if (!v) return wx.showToast({ title: '配置值不能为空', icon: 'none' })
        admin.updateSetting(item.key, v)
          .then(() => { wx.showToast({ title: '已保存（留痕）', icon: 'success' }); this.load() })
          .catch((er) => wx.showToast({ title: (er && er.message) || '保存失败', icon: 'none' }))
      }
    })
  }
})