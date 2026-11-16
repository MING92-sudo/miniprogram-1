// 电梯档案（M2，屏 08）：平台同步字段 + 本地维护字段（现场更正）+ 设备码 + 导出台账（双档权限）。
const { getElevatorProfile, updateElevatorLocal } = require('../../../services/elevator')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
const { TOAST_TEXT, ELEVATOR_TEXT } = require('../../../constants/index')

const LOCAL_FIELDS = {
  projectName: { label: '项目名称（小区名称）', placeholder: '请输入小区名称', required: true },
  address: { label: '安装地址', placeholder: '请输入安装地址' },
  brand: { label: '品牌', placeholder: '请输入品牌' },
  manufacturer: { label: '制造单位', placeholder: '请输入制造单位' },
  productNo: { label: '产品编号', placeholder: '请输入产品编号' },
  driveMode: { label: '驱动方式', placeholder: '请输入驱动方式' },
  ratedLoad: { label: '额定载重', placeholder: '请输入额定载重（kg）', numeric: true },
  ratedSpeed: { label: '额定速度', placeholder: '请输入额定速度（m/s）', numeric: true },
  stationsDoors: { label: '层站/门', placeholder: '请输入层站/门' },
  nextCheckDate: { label: '下次检验日期', placeholder: 'yyyy-MM-dd' },
  nextMaintenanceDate: { label: '下次维保日期', placeholder: 'yyyy-MM-dd' }
}

definePage(ROUTES['/pages/elevator/detail/index'], {
  data: { id: '', profile: null, overview: null, loading: true, error: '', localFields: [], text: ELEVATOR_TEXT },
  onLoad(query) {
    this.setData({ id: (query && query.id) || '' })
    this.fetchProfile()
  },
  fetchProfile() {
    this.setData({ loading: true, error: '' })
    getElevatorProfile(this.data.id)
      .then((profile) => {
        const local = (profile && profile.local) || {}
        const localFields = Object.keys(LOCAL_FIELDS).map((k) => ({
          key: k, label: LOCAL_FIELDS[k].label, value: String(local[k] || '')
        }))
        this.setData({ profile, overview: (profile && profile.overview) || null, localFields, loading: false })
      })
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || TOAST_TEXT.LOAD_FAILED }))
  },
  onRetry() { this.fetchProfile() },
  onBack() { nav.back() },
  onEditLocal(e) {
    const field = e.currentTarget.dataset.field
    const cfg = LOCAL_FIELDS[field]
    if (!cfg) return
    const local = (this.data.profile && this.data.profile.local) || {}
    let current = String(local[field] || '')
    if (cfg.numeric) current = current.replace(/[^\d.]/g, '')
    wx.showModal({
      title: cfg.label, editable: true, placeholderText: cfg.placeholder, content: current,
      success: (res) => {
        if (!res.confirm) return
        const v = (res.content || '').trim()
        if (cfg.required && !v) return wx.showToast({ title: cfg.label + '不能为空', icon: 'none' })
        if (v === current) return
        this.submitLocal(field, v)
      }
    })
  },
  submitLocal(field, v) {
    updateElevatorLocal(this.data.id, { [field]: v })
      .then(() => { wx.showToast({ title: '已保存', icon: 'success' }); this.fetchProfile() })
      .catch((e) => wx.showToast({ title: (e && e.message) || TOAST_TEXT.SAVE_FAILED, icon: 'none' }))
  },
  onExport() { wx.showToast({ title: '导出台账由管理端办理', icon: 'none' }) }
})