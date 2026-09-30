// 应急演练台账（TSG T5002 第五条(三)：每半年至少 1 轮，覆盖本单位在保的全部电梯品种）
const { getDrills, createDrill } = require('../../services/compliance')

const CATEGORY_OPTIONS = ['曳引驱动电梯', '液压驱动电梯', '杂物电梯', '自动扶梯与自动人行道']
const SCENE_OPTIONS = ['困人救援', '火灾', '停电', '其他']

Page({
  data: {
    list: [],
    coverage: { covered: [], missing: [] },
    categories: CATEGORY_OPTIONS,
    categoryIndex: -1,
    scenes: SCENE_OPTIONS,
    sceneIndex: -1,
    drillDate: '',
    participants: '',
    process: '',
    problems: '',
    actions: '',
    showForm: false,
    submitting: false
  },

  onShow() {
    this.fetchList()
  },

  async fetchList() {
    try {
      const data = await getDrills()
      this.setData({
        list: data.list || [],
        coverage: data.coverage || { covered: [], missing: [] }
      })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
  },

  toggleForm() {
    this.setData({ showForm: !this.data.showForm })
  },

  onDateChange(e) {
    this.setData({ drillDate: e.detail.value })
  },

  onCategoryChange(e) {
    this.setData({ categoryIndex: Number(e.detail.value) })
  },

  onSceneChange(e) {
    this.setData({ sceneIndex: Number(e.detail.value) })
  },

  onInput(e) {
    this.setData({ [e.currentTarget.dataset.field]: e.detail.value })
  },

  async onSubmit() {
    if (!this.data.drillDate) return wx.showToast({ title: '请选择演练日期', icon: 'none' })
    if (this.data.categoryIndex < 0) return wx.showToast({ title: '请选择电梯品种', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await createDrill({
        drillDate: this.data.drillDate,
        category: this.data.categories[this.data.categoryIndex],
        scene: this.data.sceneIndex > -1 ? this.data.scenes[this.data.sceneIndex] : '',
        participants: this.data.participants,
        process: this.data.process,
        problems: this.data.problems,
        actions: this.data.actions
      })
      wx.showToast({ title: '已登记', icon: 'success' })
      this.setData({
        showForm: false, drillDate: '', categoryIndex: -1, sceneIndex: -1,
        participants: '', process: '', problems: '', actions: ''
      })
      this.fetchList()
    } catch (e) {
      wx.showToast({ title: e.message || '登记失败', icon: 'none' })
    }
    this.setData({ submitting: false })
  }
})
