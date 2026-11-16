// KPI 行（M2，屏 01）：items=[{value,label,alert}]，alert 项橙红提亮
Component({
  options: { addGlobalClass: true },
  properties: {
    items: { type: Array, value: [] }
  },
  methods: {
    onTap(e) { this.triggerEvent('action', { item: e.currentTarget.dataset.item }) }
  }
})