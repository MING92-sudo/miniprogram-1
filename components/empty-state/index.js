// 空态双分支（M2，屏 13）：mode=empty 本来没有数据（引导创建/接单）；mode=filtered 筛选后 0 条（引导清空筛选）。
Component({
  options: { addGlobalClass: true },
  properties: {
    mode: { type: String, value: 'empty' },
    icon: { type: String, value: '' },
    title: { type: String, value: '暂无数据' },
    desc: { type: String, value: '' },
    actionText: { type: String, value: '' },
    secondaryText: { type: String, value: '' }
  },
  data: { ico: 'i-inbox' },
  observers: {
    'mode, icon': function (mode, icon) {
      this.setData({ ico: icon || (mode === 'filtered' ? 'i-filter' : 'i-inbox') })
    }
  },
  methods: {
    onAction() { this.triggerEvent('action') },
    onSecondary() { this.triggerEvent('secondary') }
  }
})