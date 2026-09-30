// 电梯详细档案（docs/01 §3.4.1）
// 数据来源分两组：platform = 监管平台 2.7 自动获取（docs/04 B.7）；local = 本地维护
const { getElevatorProfile } = require('../../services/elevator')

Page({
  data: {
    elevatorId: '',
    profile: null,
    loading: true
  },

  onLoad(query) {
    this.setData({ elevatorId: query.elevatorId || '' })
    this.fetchProfile()
  },

  async fetchProfile() {
    try {
      const profile = await getElevatorProfile(this.data.elevatorId)
      this.setData({ profile: profile, loading: false })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  copyEntityId() {
    const id = this.data.profile && this.data.profile.platform.useUnitEntityId
    if (!id) return
    wx.setClipboardData({ data: id })
  }
})
