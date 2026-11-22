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