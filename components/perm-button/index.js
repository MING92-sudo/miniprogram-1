// 双档权限按钮（M2，roles-permissions §8.4）：
// 一档 永久权限拒绝（无权限码）= 原生 disabled + 中文「所需能力·允许角色」注（不可聚焦）；
// 二档 临时前置未满足（有权限但 gate）= aria-disabled + catchtap 拦截 + 原因文本（保持可聚焦）。
// 小程序无 DOM 捕获阶段，catchtap 不冒泡即等价拦截；后端校验仍是唯一安全边界。
Component({
  options: { addGlobalClass: true },
  properties: {
    perm: { type: String, value: '' },
    capability: { type: String, value: '该功能' },
    allowRoles: { type: String, value: '请联系管理员' },
    gate: { type: Boolean, value: false },
    gateReason: { type: String, value: '' },
    type: { type: String, value: 'primary' },
    size: { type: String, value: '' },
    text: { type: String, value: '' }
  },
  data: { tier: 0 },
  lifetimes: {
    attached() { this.refresh() }
  },
  pageLifetimes: {
    show() { this.refresh() }
  },
  observers: {
    'gate, perm': function () { this.refresh() }
  },
  methods: {
    refresh() {
      const app = getApp()
      const perms = (app && app.globalData && app.globalData.perms) || []
      const hasPerm = !this.data.perm || perms.indexOf(this.data.perm) > -1
      const tier = !hasPerm ? 1 : (this.data.gate ? 2 : 0)
      this.setData({ tier })
    },
    onTap() {
      if (this.data.tier === 1) return
      if (this.data.tier === 2) {
        if (this.data.gateReason) wx.showToast({ title: this.data.gateReason, icon: 'none' })
        return
      }
      this.triggerEvent('tap')
    }
  }
})