// 失败态三件套（M2，屏 15）：可读原因 + 可重试 + 可退路；失败不清空本地已填数据。
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