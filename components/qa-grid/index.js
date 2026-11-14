// 快捷动作宫格（M2，屏 01）：items=[{key,text,icon}]，icon 经 ICONS 映射本地类；event action(detail={key,item})
const { ICONS } = require('../../config/icons')
Component({
  options: { addGlobalClass: true },
  properties: {
    items: { type: Array, value: [] }
  },
  data: { icons: ICONS },
  methods: {
    onTap(e) {
      const { key, index } = e.currentTarget.dataset
      this.triggerEvent('action', { key, item: this.data.items[index] })
    }
  }
})