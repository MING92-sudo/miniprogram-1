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