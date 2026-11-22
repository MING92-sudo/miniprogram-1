Component({
  options: { addGlobalClass: true },
  properties: {
    items: { type: Array, value: [] }
  },
  methods: {
    onTap(e) { this.triggerEvent('action', { item: e.currentTarget.dataset.item }) }
  }
})