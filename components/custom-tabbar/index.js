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