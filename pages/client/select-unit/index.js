const client = require('../../../services/client')
const auth = require('../../../services/auth')
const { clearUnitSession } = require('../../../services/unit')

const SEL_KEY = 'em_client_selection'

Page({
  data: { units: [], loading: false, phone: '' },
  onLoad() {
    let sel
    try { sel = wx.getStorageSync(SEL_KEY) || {} } catch (e) { sel = {} }
    if (!sel.selectionToken || !Array.isArray(sel.units) || !sel.units.length) {
      wx.showToast({ title: '选单位凭证已失效', icon: 'none' })
      setTimeout(() => wx.reLaunch({ url: '/pages/login/index' }), 800)
      return
    }
    this.setData({ units: sel.units, phone: sel.phone || '' })
  },
  async choose(e) {
    if (this.data.loading) return
    const unitId = e.currentTarget.dataset.unitid
    let sel
    try { sel = wx.getStorageSync(SEL_KEY) || {} } catch (err) { sel = {} }
    this.setData({ loading: true })
    try {
      const data = await client.selectUnit(sel.selectionToken, unitId)
      try { wx.removeStorageSync(SEL_KEY) } catch (err) { /* ignore */ }
      clearUnitSession()
      auth.applyLoginResult(data)
      getApp().setAuth(data)
      wx.showToast({ title: '登录成功', icon: 'success' })
      setTimeout(() => wx.reLaunch({ url: '/pages/client/home/index' }), 300)
    } catch (err) {
      wx.showToast({ title: err.message || '选择单位失败', icon: 'none' })
      this.setData({ loading: false })
    }
  },
  backLogin() {
    try { wx.removeStorageSync(SEL_KEY) } catch (e) { /* ignore */ }
    wx.reLaunch({ url: '/pages/login/index' })
  }
})
