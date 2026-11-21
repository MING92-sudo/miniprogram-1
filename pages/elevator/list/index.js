// 电梯列表（M2）：搜索 + 状态收敛（后端谓词）+ 四态；tab 宿主。
const { getElevatorList } = require('../../../services/elevator')
const unit = require('../../../services/unit')
const { isCustomerPrincipal } = require('../../../utils/auth')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')
const { EMPTY_TEXT, ELEVATOR_TEXT } = require('../../../constants/index')

definePage(ROUTES['/pages/elevator/list/index'], {
  data: { keyword: '', allList: [], list: [], loading: true, error: '', emptyMode: 'empty', emptyTitle: '', emptyDesc: '', text: ELEVATOR_TEXT },
  onShow() { this.fetchList() },
  fetchList() {
    this.setData({ loading: true, error: '' })
    // 客户主体必须走门户接口 /unit/elevators：/elevators 是员工读接口，按员工数据域过滤，
    // 客户令牌调它恒返回空数组 → 客户看到「暂无电梯」而本单位明明有梯（小程序实测）。
    const request = isCustomerPrincipal() ? unit.getUnitElevators() : getElevatorList()
    request
      .then((list) => {
        const all = list || []
        const filtered = this.applyFilter(all, this.data.keyword)
        this.setData({ allList: all, list: filtered, loading: false, emptyMode: all.length ? 'filtered' : 'empty', emptyTitle: all.length ? '没有匹配的电梯' : EMPTY_TEXT.ELEVATOR, emptyDesc: all.length ? '换个关键词试试，或清空搜索。' : '电梯建档后会出现在这里。' })
      })
      .catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.fetchList() },
  onSearchInput(e) {
    const keyword = String(e.detail.value || '').trim()
    const filtered = this.applyFilter(this.data.allList, keyword)
    this.setData({ keyword, list: filtered, emptyMode: this.data.allList.length ? 'filtered' : 'empty' })
  },
  onClearSearch() { this.setData({ keyword: '', list: this.data.allList }) },
  applyFilter(list, keyword) {
    if (!keyword) return list
    const kw = keyword.toLowerCase()
    return list.filter((el) => [el.elevatorName, el.elevatorCode, el.deviceCode, el.regCode, el.model, el.projectName]
      .some((f) => f && String(f).toLowerCase().indexOf(kw) > -1))
  },
  goDetail(e) { nav.open('/pages/elevator/detail/index?id=' + e.currentTarget.dataset.id) }
})