// 自定义底部导航（M2，规格 §1.2）：4–5 项自适应等宽、角标、safe-area；tab 切换 reLaunch 清栈。
// 数据源 globalData.tabs/badges（/me/menus 下发）；active 由当前路由匹配。
const { ICONS } = require('../../config/icons')
const nav = require('../../utils/nav')

Component({
  options: { addGlobalClass: true },
  data: { tabs: [], active: '', badges: {}, icons: ICONS },
  lifetimes: {
    attached() {
      this.sync()
      // 菜单晚到时补渲染：applyToGlobal 通知后重新 sync，避免底栏停在空态
      const app = getApp()
      if (app && typeof app.onMenusUpdated === 'function') {
        this._msync = () => this.sync()
        app.onMenusUpdated(this._msync)
      }
    },
    detached() {
      const app = getApp()
      if (app && typeof app.offMenusUpdated === 'function') app.offMenusUpdated(this._msync)
    }
  },
  pageLifetimes: {
    show() { this.sync() }
  },
  methods: {
    sync() {
      const app = getApp()
      const g = (app && app.globalData) || {}
      const pages = getCurrentPages()
      const cur = pages.length ? '/' + pages[pages.length - 1].route : ''
      const tabs = g.tabs || []
      let active = ''
      tabs.forEach((t) => {
        if (t.path.split('?')[0] === cur) active = t.key
      })
      this.setData({ tabs, active, badges: g.badges || {} })
    },
    onTap(e) {
      const { path, key } = e.currentTarget.dataset
      if (key === this.data.active) return
      nav.open(path)
    }
  }
})