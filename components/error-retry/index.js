Component({
  options: { addGlobalClass: true },
  properties: {
    title: { type: String, value: '加载失败' },
    message: { type: String, value: '' },
    code: { type: String, value: '' }
  },
  methods: {
    onRetry() { this.triggerEvent('retry') },
    onBack() { this.triggerEvent('back') }
  }
})