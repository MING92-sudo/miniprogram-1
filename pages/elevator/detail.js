// 电梯设备详情：平台同步字段 + 本地维护字段（docs/01 §3.4.1）
const { getElevatorProfile, updateElevatorProjectName } = require('../../services/elevator')
const { TOAST_TEXT, LOADING_TEXT } = require('../../constants/index')

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

  // 现场更正项目名称（小区名称）：管理端可能写错，维保人员核对后可直接改（docs/01 §3.4.1）
  onEditProjectName() {
    const current = (this.data.profile && this.data.profile.local && this.data.profile.local.projectName) || ''
    wx.showModal({
      title: '项目名称（小区名称）',
      editable: true,
      placeholderText: '请输入小区名称',
      content: current,
      success: (res) => {
        if (!res.confirm) return
        const v = (res.content || '').trim()
        if (!v) {
          wx.showToast({ title: '项目名称不能为空', icon: 'none' })
          return
        }
        if (v === current) return
        this.submitProjectName(v)
      }
    })
  },

  async submitProjectName(v) {
    try {
      await updateElevatorProjectName(this.data.id, v)
      wx.showToast({ title: '已保存', icon: 'success' })
      this.fetchProfile()
    } catch (e) {
      wx.showToast({ title: e.message || TOAST_TEXT.SAVE_FAILED, icon: 'none' })
    }
  }
})
