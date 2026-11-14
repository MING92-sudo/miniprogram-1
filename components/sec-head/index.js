// 节标题（M2）：左标题 + 右可点文本动作
Component({
  options: { addGlobalClass: true },
  properties: {
    title: { type: String, value: '' },
    actionText: { type: String, value: '' }
  },
  methods: {
    onAction() { this.triggerEvent('action') }
  }
})