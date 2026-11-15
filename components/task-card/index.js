// 任务卡（M2，屏 01）：时间 + 标题 + 描述 + 状态标签；整卡可点
Component({
  options: { addGlobalClass: true },
  properties: {
    time: { type: String, value: '' },
    title: { type: String, value: '' },
    desc: { type: String, value: '' },
    tagText: { type: String, value: '' },
    tagTone: { type: String, value: 'dim' }
  },
  methods: {
    onTap() { this.triggerEvent('tap') }
  }
})