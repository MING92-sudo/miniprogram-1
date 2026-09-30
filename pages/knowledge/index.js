// 知识库：条目检索与查看
const { getKnowledgeList } = require('../../services/knowledge')
const { ensureLogin } = require('../../utils/guard')

Page({
  data: {
    keyword: '',
    list: [],
    loading: false,
    expandedId: ''
  },

  onShow() {
    // 首次 onShow 发生在路由进行中，守卫 reLaunch 会与其竞态
    // （routeDone with a webviewId xxx is not found），首次守卫延迟到 onReady
    if (!this._authReady) return
    if (!ensureLogin()) return
    this.fetchList()
  },

  onReady() {
    this._authReady = true
    if (!ensureLogin()) return
    this.fetchList()
  },

  async fetchList() {
    this.setData({ loading: true })
    try {
      const data = await getKnowledgeList({ keyword: this.data.keyword })
      this.setData({ list: (data && data.list) || [] })
    } catch (e) {
      wx.showToast({ title: e.message || '加载失败', icon: 'none' })
    }
    this.setData({ loading: false })
  },

  onSearch(e) {
    this.setData({ keyword: e.detail.value })
    this.fetchList()
  },

  // 展开/收起条目详情
  onItemTap(e) {
    const id = e.currentTarget.dataset.id
    this.setData({ expandedId: this.data.expandedId === id ? '' : id })
  }
})
