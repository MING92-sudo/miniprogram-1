// 内部审核（M4，安装主管）：移交单 + 资料 + 查验结论只读核对。
// 注：正式审核通过/退回（PENDING_INST_REVIEW）与作废端点后端尚未落地（docs/12 缺口），本页先做只读核对投影，审批动作待后端。
const { getHandoverDetail, getHandoverChecklist } = require('../../../services/handover')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const nav = require('../../../utils/nav')

definePage(ROUTES['/pkg-handover/pages/review/index'], {
  data: { id: '', detail: null, check: null, loading: true, error: '' },
  onLoad(query) { this.setData({ id: (query && query.id) || '' }); this.load() },
  onBack() { nav.back() },
  load() {
    this.setData({ loading: true, error: '' })
    Promise.all([
      getHandoverDetail(this.data.id),
      getHandoverChecklist(this.data.id).catch(() => null)
    ]).then(([detail, cl]) => {
      this.setData({ detail, check: cl && cl.check, loading: false })
    }).catch((e) => this.setData({ loading: false, error: (e && e.message) || '加载失败' }))
  },
  onRetry() { this.load() },
  goInspect() { nav.open('/pkg-handover/pages/inspect/index?id=' + this.data.id) },
  goUpload() { nav.open('/pkg-handover/pages/upload/index?handoverId=' + this.data.id) }
})