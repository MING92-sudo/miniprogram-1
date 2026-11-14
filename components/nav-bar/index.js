// 自定义导航栏（M2，规格 §1.2/§2.5）：状态栏高度自适应 + 胶囊避让 + 返回/品牌区。
// 品牌 = 圆形徽标裁切件 + 博威无纸化（D5：BOWEI 字母不上页）。
const nav = require('../../utils/nav')

Component({
  options: { addGlobalClass: true },
  properties: {
    title: { type: String, value: '' },
    brand: { type: Boolean, value: false },
    back: { type: Boolean, value: true }
  },
  data: { statusBarHeight: 20, navHeight: 44, padRight: 96 },
  lifetimes: {
    attached() {
      let statusBarHeight = 20
      let navHeight = 44
      let padRight = 96
      try {
        const wi = wx.getWindowInfo()
        statusBarHeight = wi.statusBarHeight || 20
        const cap = wx.getMenuButtonBoundingClientRect()
        navHeight = (cap.top - statusBarHeight) * 2 + cap.height
        padRight = wi.windowWidth - cap.left + 8
      } catch (e) { /* 低版本基础库兜底默认值 */ }
      this.setData({ statusBarHeight, navHeight, padRight })
    }
  },
  methods: {
    onBack() { nav.back() }
  }
})