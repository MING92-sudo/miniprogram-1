// 电梯设备详情：平台同步字段 + 本地维护字段（docs/01 §3.4.1）
const { getElevatorProfile, updateElevatorLocal } = require('../../services/elevator')
const { TOAST_TEXT, LOADING_TEXT } = require('../../constants/index')

// 本地维护信息可现场更正字段（经纬度=签到围栏、使用单位负责人=使用单位档案，均不开放）
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

Page({
  data: {
    id: '',
    profile: null,
    loading: true,
    loadingText: LOADING_TEXT
  },

  onLoad(query) {
    this.setData({ id: query.id || '' })
    this.fetchProfile()
  },

  async fetchProfile() {
    this.setData({ loading: true })
    try {
      const profile = await getElevatorProfile(this.data.id)
      this.setData({ profile, loading: false })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  // 现场更正本地维护字段（docs/01 §3.4.1）：点按弹出可编辑输入框，提交走 PUT /elevators/:id/local
  onEditLocal(e) {
    const field = e.currentTarget.dataset.field
    const cfg = LOCAL_FIELDS[field]
    if (!cfg) return
    const local = (this.data.profile && this.data.profile.local) || {}
    let current = String(local[field] || '')
    if (cfg.numeric) {
      current = current.replace(/[^\d.]/g, '') // 额定载重/速度显示带单位，编辑时剥离
    }
    wx.showModal({
      title: cfg.label,
      editable: true,
      placeholderText: cfg.placeholder,
      content: current,
      success: (res) => {
        if (!res.confirm) return
        const v = (res.content || '').trim()
        if (cfg.required && !v) {
          wx.showToast({ title: cfg.label + '不能为空', icon: 'none' })
          return
        }
        if (v === current) return
        this.submitLocal(field, v)
      }
    })
  },

  async submitLocal(field, v) {
    try {
      await updateElevatorLocal(this.data.id, { [field]: v })
      wx.showToast({ title: '已保存', icon: 'success' })
      this.fetchProfile()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
  }
})
