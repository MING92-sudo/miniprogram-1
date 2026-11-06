// 电梯设备列表：搜索定位电梯，点击查看设备档案（docs/01 §3.4.1）
const { getElevatorList } = require('../../services/elevator')
const { ensureLogin } = require('../../utils/guard')
const { EMPTY_TEXT, TOAST_TEXT, LOADING_TEXT } = require('../../constants/index')

Page({
  data: {
    keyword: '',
    allList: [],
    list: [],
    loading: true,
    emptyHint: '',
    loadingText: LOADING_TEXT
  },

  onShow() {
    // 首次 onShow 发生在路由进行中，守卫 reLaunch 会与其竞态，首次守卫延迟到 onReady
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
      const list = (await getElevatorList()) || []
      this.setData({
        allList: list,
        list: this.applyFilter(list, this.data.keyword),
        loading: false,
        emptyHint: this.computeEmptyHint(list, this.applyFilter(list, this.data.keyword))
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  onSearchInput(e) {
    const keyword = String(e.detail.value || '').trim()
    this.setData({
      keyword: keyword,
      list: this.applyFilter(this.data.allList, keyword),
      emptyHint: this.computeEmptyHint(this.data.allList, this.applyFilter(this.data.allList, keyword))
    })
  },

  // 空态提示：无电梯数据 vs 搜索无匹配
  computeEmptyHint(allList, filtered) {
    if (!allList.length) return EMPTY_TEXT.ELEVATOR
    if (!filtered.length) return EMPTY_TEXT.ELEVATOR_SEARCH
    return ''
  },

  // 关键字匹配：名称/电梯编号/设备代码/登记证号/型号/项目名称
  applyFilter(list, keyword) {
    if (!keyword) return list
    const kw = keyword.toLowerCase()
    return list.filter(function (el) {
      const fields = [el.elevatorName, el.elevatorCode, el.deviceCode, el.regCode, el.model, el.projectName]
      return fields.some(function (f) { return f && String(f).toLowerCase().indexOf(kw) > -1 })
    })
  },

  goDetail(e) {
    wx.navigateTo({ url: '/pages/elevator/detail?id=' + e.currentTarget.dataset.id })
  }
})
