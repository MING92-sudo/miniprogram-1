// 签署确认（M5，屏 11）：一次性任务 taskId+token（免登录）；核对项目明细/异常整改/现场照片（2026-11-20
// sign-view 扩项，docs/14 §4.7）→ 勾选确认门控（§8.4 第二档）→ 手写签名 → 时间戳/GPS（后端写入）→ 锁定。
// token 重放/转发由后端一次性校验拦截。
// V2.80：①签字 SLA 倒计时（sign-view 下发 signDeadline/serverNow，按服务器时钟对齐，不信任本机时间）；
// ②拒签原因分类码（R1—R5 面板选择，R5 必填说明）；③批量签署模式（mode=batch&ids=…，同一签名一次确认
// ≤20 条，逐条结果据实提示）；④签名先经 services/upload 上传再提交（签名统一走 upload，AGENTS §4.1）。
const unit = require('../../../services/unit')
const { uploadImage, uploadSignatureByToken } = require('../../../services/upload')
const { definePage } = require('../../../utils/guard')
const { ROUTES } = require('../../../config/routes')
const { buildRecordSummary } = require('../../utils/record-view')
const { parseTime } = require('../../../utils/util')
const { REFUSE_REASON_OPTIONS } = require('../../../constants/index')
const nav = require('../../../utils/nav')

function pad(n) { return (n < 10 ? '0' : '') + n }
// 剩余时长文案：>1 天「X 天 X 小时」，否则「HH:mm:ss」；≤0 视为超期
function remainText(ms) {
  if (ms <= 0) return ''
  const sec = Math.floor(ms / 1000)
  if (sec >= 86400) return Math.floor(sec / 86400) + ' 天 ' + Math.floor((sec % 86400) / 3600) + ' 小时'
  return pad(Math.floor(sec / 3600)) + ':' + pad(Math.floor((sec % 3600) / 60)) + ':' + pad(sec % 60)
}

definePage(ROUTES['/pkg-client/pages/sign/index'], {
  data: {
    rid: '', token: '', view: null, summary: null, confirmed: false, refused: false,
    hasDrawn: false, consent: false, submitting: false, loadError: '',
    // V2.80 SLA 倒计时
    slaText: '', slaOver: false,
    // V2.80 拒签分类码面板
    refuseOpen: false, refuseCode: '', refuseReason: '', refuseOptions: REFUSE_REASON_OPTIONS, refuseSubmitting: false,
    // V2.80 批量签署模式
    batch: false, batchIds: [], batchList: [], batchFailed: []
  },
  onLoad(query) {
    const q = query || {}
    if (q.mode === 'batch' && q.ids) {
      const ids = String(q.ids).split(',').map((s) => s.trim()).filter(Boolean)
      this.setData({ batch: true, batchIds: ids })
      this.fetchBatch()
      return
    }
    this.setData({ rid: (q.taskId || q.rid) || '', token: q.token || '' })
    this.fetchView()
  },
  onReady() { this.initCanvas() },
  onUnload() { this.clearSlaTimer() },
  onBack() { nav.back() },

  // ── 单笔签署（token 链）──
  fetchView() {
    unit.getSignView(this.data.rid, this.data.token)
      .then((view) => {
        const items = (view.items || []).map((it, i) => Object.assign({}, it, {
          seq: i + 1,
          resultText: it.result === 'NORMAL' ? '正常' : (it.result === 'ABNORMAL' ? '异常' : (it.result === 'NA' ? '不适用' : (it.result || '—'))),
          resultClass: it.result === 'ABNORMAL' ? 'ab' : (it.result === 'NORMAL' ? 'ok' : ''),
          valueShow: it.value !== null && it.value !== undefined && it.value !== ''
            ? String(it.value) + (it.valueUnit || '') : (it.valueText || '')
        }))
        this.setData({
          view: Object.assign({}, view, { items }),
          summary: buildRecordSummary(view.items || []),
          confirmed: !!view.confirmed, refused: view.confirmStatus === 'REFUSED', loadError: ''
        })
        this.startSlaTimer(view)
      })
      .catch((e) => this.setData({ loadError: (e && e.message) || '链接无效或已失效' }))
  },

  // ── V2.80 SLA 倒计时（serverNow 对齐时钟，1s 步进）──
  startSlaTimer(view) {
    this.clearSlaTimer()
    if (!view || !view.signDeadline) return
    const deadline = parseTime(view.signDeadline)
    // 服务器时钟偏移：deadline 比较一律用「本机时间 + 偏移」推算的服务器当前时刻
    this.slaOffset = view.serverNow ? parseTime(view.serverNow) - Date.now() : 0
    const tick = () => {
      const serverNow = Date.now() + (this.slaOffset || 0)
      const ms = deadline - serverNow
      if (ms <= 0) {
        this.setData({ slaText: '已超过签字截止时间（' + view.signDeadline + '），请尽快签署', slaOver: true })
        this.clearSlaTimer()
        return
      }
      this.setData({ slaText: '签字截止 ' + view.signDeadline + ' · 剩余 ' + remainText(ms), slaOver: false })
    }
    tick()
    this.slaTimer = setInterval(tick, 1000)
  },
  clearSlaTimer() { if (this.slaTimer) { clearInterval(this.slaTimer); this.slaTimer = null } },

  // ── 签名画布 ──
  initCanvas() {
    wx.createSelectorQuery().select('#sign-canvas').fields({ node: true, size: true }).exec((res) => {
      const canvas = res && res[0] && res[0].node
      if (!canvas) return
      const ctx = canvas.getContext('2d')
      const dpr = wx.getWindowInfo().pixelRatio || 2
      canvas.width = res[0].width * dpr; canvas.height = res[0].height * dpr
      ctx.scale(dpr, dpr); ctx.lineWidth = 4; ctx.lineCap = 'round'; ctx.strokeStyle = '#000000'
      this.canvas = canvas; this.ctx = ctx
    })
  },
  onTouchStart(e) { if (!this.ctx) return; const t = e.touches[0]; this.ctx.beginPath(); this.ctx.moveTo(t.x, t.y) },
  onTouchMove(e) { if (!this.ctx) return; const t = e.touches[0]; this.ctx.lineTo(t.x, t.y); this.ctx.stroke(); if (!this.data.hasDrawn) this.setData({ hasDrawn: true }) },
  onClear() { if (!this.ctx) return; this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height); this.setData({ hasDrawn: false }) },
  toggleConsent() { this.setData({ consent: !this.data.consent }) },

  // 导出签名并上传（签名统一走 services/upload，返回 {fileId,url}）：
  // 批量模式=已登录客户走 /files/upload；单条免登录 token 链走 signature-upload（无会话，token 即凭证）
  exportAndUpload() {
    return new Promise((resolve, reject) => {
      wx.canvasToTempFilePath({
        canvas: this.canvas,
        success: (res) => {
          const up = this.data.batch
            ? uploadImage(res.tempFilePath)
            : uploadSignatureByToken(this.data.rid, this.data.token, res.tempFilePath)
          up.then(resolve).catch(reject)
        },
        fail: () => reject({ message: '签名导出失败' })
      })
    })
  },

  // 勾选门控（第二档）：未勾选拦截 + 原因；已勾选未签名拦截
  onSubmit() {
    if (this.data.submitting) return
    if (!this.data.consent) return wx.showToast({ title: '请先勾选「我已核对…」再签署', icon: 'none' })
    if (!this.data.hasDrawn) return wx.showToast({ title: '请在签字区手写签名', icon: 'none' })
    this.setData({ submitting: true })
    this.exportAndUpload()
      .then((up) => {
        if (this.data.batch) return this.submitBatch(up)
        return unit.confirmByToken(this.data.rid, this.data.token,
          { signatureFileId: up.fileId, signatureUrl: up.url || '' })
          .then(() => {
            wx.showToast({ title: '已签署', icon: 'success' })
            this.setData({ submitting: false })
            this.fetchView()
          })
      })
      .catch((e) => {
        this.setData({ submitting: false })
        wx.showToast({ title: (e && e.message) || '签署失败', icon: 'none' })
      })
  },

  // ── V2.80 批量签署（客户令牌链路，待办页勾选进入）──
  fetchBatch() {
    this.setData({ loadError: '' })
    Promise.all(this.data.batchIds.map((id) => unit.getRecord(id).catch((e) => ({ id, loadError: (e && e.message) || '加载失败' }))))
      .then((rows) => {
        const broken = rows.filter((r) => r.loadError)
        // 部分加载失败 → 清空可签清单：签名板/提交按钮门控在 batchList.length 上，
        // 绝不让用户对从未核看到的记录落笔（loadError 卡片引导返回重试）
        this.setData({
          batchList: broken.length ? [] : rows.map((r) => Object.assign({}, r, {
            workDate: (r.checkoutTime || r.checkinTime || '').slice(0, 10)
          })),
          loadError: broken.length ? '有 ' + broken.length + ' 条记录加载失败（' + (broken[0].loadError || '') + '），请返回重试' : ''
        })
      })
  },
  submitBatch(up) {
    return unit.batchConfirmRecords(this.data.batchIds,
      { signatureFileId: up.fileId, signatureUrl: up.url || '' })
      .then((res) => {
        this.setData({ submitting: false, batchFailed: (res && res.failed) || [] })
        const signed = (res && res.signed) || 0
        const failed = ((res && res.failed) || []).length
        if (!failed) {
          wx.showToast({ title: '已批量签署 ' + signed + ' 条', icon: 'success' })
          setTimeout(() => nav.back(), 1200)
        } else {
          wx.showToast({ title: signed + ' 条已签，' + failed + ' 条失败', icon: 'none' })
        }
      })
  },

  // ── V2.80 拒签分类码（R1—R5；R5 必填说明）──
  openRefuse() { this.setData({ refuseOpen: true, refuseCode: '', refuseReason: '' }) },
  closeRefuse() { if (!this.data.refuseSubmitting) this.setData({ refuseOpen: false }) },
  onRefuseCodeChange(e) { this.setData({ refuseCode: e.currentTarget.dataset.code }) },
  onRefuseReasonInput(e) { this.setData({ refuseReason: e.detail.value }) },
  submitRefuse() {
    if (this.data.refuseSubmitting) return
    const code = this.data.refuseCode
    if (!code) return wx.showToast({ title: '请选择拒签原因分类', icon: 'none' })
    const opt = REFUSE_REASON_OPTIONS.find((o) => o.code === code)
    const reason = (this.data.refuseReason || '').trim()
    if (opt && opt.needDetail && !reason) {
      return wx.showToast({ title: '选择「其他」时必须填写拒签说明', icon: 'none' })
    }
    this.setData({ refuseSubmitting: true })
    unit.refuseRecordByToken(this.data.rid, this.data.token, reason, code)
      .then(() => {
        this.setData({ refuseSubmitting: false, refuseOpen: false })
        wx.showToast({ title: '已拒签退回', icon: 'success' })
        this.fetchView()
      })
      .catch((e) => {
        this.setData({ refuseSubmitting: false })
        wx.showToast({ title: (e && e.message) || '拒签失败', icon: 'none' })
      })
  },

  onShareAppMessage() {
    if (this.data.batch) return { title: '维保记录批量签署', path: '/pkg-client/pages/todo/index' }
    return { title: '维保记录确认 — ' + (this.data.view ? this.data.view.elevatorName : '电梯'), path: '/pkg-client/pages/sign/index?taskId=' + this.data.rid + '&token=' + this.data.token }
  }
})
