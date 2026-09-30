// 电梯列表（市监局对接卡片下钻）：全部已对接电梯，点击查看档案
const { getElevatorList } = require('../../services/elevator')

Page({
  data: {
    list: [],
    loading: true
  },

  onShow() {
    this.fetchList()
  },

  fetchList() {
    const self = this
    getElevatorList()
      .then(function (list) {
        self.setData({ list: list || [], loading: false })
      })
      .catch(function (e) {
        self.setData({ loading: false })
        wx.showToast({ title: e.message || '加载失败', icon: 'none' })
      })
  },

  goProfile(e) {
    wx.navigateTo({
      url: `/pages/elevator/profile?elevatorId=${e.currentTarget.dataset.id}`
    })
  }
})
