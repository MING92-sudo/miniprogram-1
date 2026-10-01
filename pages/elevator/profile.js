// 电梯详细档案
// 数据来源分两组：platform = 平台自动获取；local = 本地维护
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
