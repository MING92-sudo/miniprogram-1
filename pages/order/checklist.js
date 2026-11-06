// 作业清单：头部上下文 + 按频次分节（表A-1/A-2…）+ 三态进度，克制工业风
const { getChecklist, runItemThisTime } = require('../../services/order')
const { CHECK_RESULT_TEXT, TOAST_TEXT, EMPTY_TEXT } = require('../../constants/index')

const RESULT_TEXT = CHECK_RESULT_TEXT // NORMAL/ABNORMAL/NA（docs/04 A.2 枚举）

// 三态字形（排版符号代替 emoji，色 + 形双编码，杜绝仅颜色）
const GLYPH = {
  NORMAL: { text: '✓', cls: 'is-ok' },
  ABNORMAL: { text: '✗', cls: 'is-ng' },
  NA: { text: '—', cls: 'is-na' },
  PENDING: { text: '○', cls: 'is-pending' },
  SKIP: { text: '◇', cls: 'is-skip' }
}

// 单项派生字段：状态字形、结果胶囊、副行文案（异常描述 > 读数/说明书 > 要求 > 周期提示）
function decorate(i) {
  const photos = i.photos || []
  const result = i.result
  let g
  let sub = ''
  let subClass = ''
  if (i.notInThisRun) {
    g = GLYPH.SKIP
    sub = i.nextRunText || '本次无需执行'
    subClass = 'is-skip'
  } else if (result === 'ABNORMAL') {
    g = GLYPH.ABNORMAL
    sub = '隐患码 ' + (i.problemCode || '—') + (i.abnormalDesc ? ' · ' + i.abnormalDesc : '')
    subClass = 'is-ng'
  } else if (result === 'NA') {
    g = GLYPH.NA
    sub = i.skipReason || '不适用'
    subClass = 'is-na'
  } else if (result === 'NORMAL') {
    g = GLYPH.NORMAL
  } else {
    g = GLYPH.PENDING
  }
  if (!sub) {
    if (i.value != null && i.value !== '') {
      sub = '读数 ' + i.value + (i.valueUnit || '')
    } else if (i.valueText) {
      sub = i.valueText
    } else {
      sub = i.requirement || ''
    }
  }
  return Object.assign({}, i, {
    resultText: result ? RESULT_TEXT[result] : '',
    glyph: g.text,
    glyphClass: g.cls,
    resultClass: result === 'NORMAL' ? 'res-ok'
      : result === 'ABNORMAL' ? 'res-ng'
      : result === 'NA' ? 'res-na' : 'res-pending',
    sub: sub,
    subClass: subClass,
    photoCount: photos.length
  })
}

// 按 freqLabel 分节（保持模板顺序；HM 单节、TM 双节…自定义项单独一节）
function groupByFreq(items) {
  const sections = []
  const index = {}
  items.forEach(function (i) {
    const label = i.freqLabel || '检查项目'
    let s = index[label]
    if (!s) {
      s = { label: label, items: [], done: 0, total: 0 }
      index[label] = s
      sections.push(s)
    }
    s.items.push(i)
    s.total += 1
    if (i.result) s.done += 1
  })
  return sections
}

Page({
  data: {
    orderId: '',
    workTypeCode: '',
    workType: '',
    elevatorName: '',
    projectName: '',
    sections: [],
    photos: [], // 全部已拍照片汇总（含来源检查项）
    doneCount: 0,
    requiredTotal: 0,
    normalCount: 0,
    abnormalCount: 0,
    naCount: 0,
    pendingCount: 0,
    progress: 0,
    loading: true,
    emptyText: EMPTY_TEXT
  },

  onLoad(query) {
    this.setData({ orderId: query.orderId || '' })
  },

  // 从检查项填写页返回后自动刷新
  onShow() {
    if (this.data.orderId) {
      this.fetchChecklist()
    }
  },

  async fetchChecklist() {
    try {
      const checklist = await getChecklist(this.data.orderId)
      const items = ((checklist && checklist.items) || []).map(decorate)
      const photos = []
      items.forEach(function (i) {
        (i.photos || []).forEach(function (p) {
          photos.push({ src: p, itemName: i.name })
        })
      })
      // 结单口径与后端 checkout 一致：周期性条目（notInThisRun）不计入必填
      const requiredTotal = items.filter(function (i) { return !i.notInThisRun }).length
      const doneCount = items.filter(function (i) { return !i.notInThisRun && i.result }).length
      const normalCount = items.filter(function (i) { return i.result === 'NORMAL' }).length
      const abnormalCount = items.filter(function (i) { return i.result === 'ABNORMAL' }).length
      const naCount = items.filter(function (i) { return i.result === 'NA' }).length
      this.setData({
        workTypeCode: checklist.workTypeCode || '',
        workType: checklist.workType || '',
        elevatorName: checklist.elevatorName || '',
        projectName: checklist.projectName || '',
        sections: groupByFreq(items),
        photos: photos,
        doneCount: doneCount,
        requiredTotal: requiredTotal,
        normalCount: normalCount,
        abnormalCount: abnormalCount,
        naCount: naCount,
        pendingCount: requiredTotal - doneCount,
        progress: requiredTotal ? Math.round((doneCount / requiredTotal) * 100) : 0,
        loading: false
      })
    } catch (e) {
      this.setData({ loading: false })
      wx.showToast({ title: e.message || TOAST_TEXT.LOAD_FAILED, icon: 'none' })
    }
  },

  // 进入检查项填写页
  goItemForm(e) {
    const itemId = e.currentTarget.dataset.id
    wx.navigateTo({
      url: `/pages/order/item-form?orderId=${this.data.orderId}&itemId=${itemId}`
    })
  },

  // 照片汇总：点击全屏预览（可左右滑动查看本单全部照片）
  previewPhoto(e) {
    const current = e.currentTarget.dataset.src
    wx.previewImage({
      current: current,
      urls: this.data.photos.map(function (p) { return p.src })
    })
  },

  // 全部完成后进入签退
  goCheckout() {
    // 口径对齐后端 checkout：只校验非周期项（notInThisRun 不拦截）
    const missing = this.data.pendingCount
    if (missing > 0) {
      wx.showToast({ title: `还有 ${missing} 项未填写`, icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/order/checkout?orderId=${this.data.orderId}` })
  },

  // 周期性条目：本次仍要执行（docs/03 §6.1）
  async onRunThisTime(e) {
    const itemId = e.currentTarget.dataset.id
    try {
      await runItemThisTime(this.data.orderId, itemId)
      wx.showToast({ title: '已加入本次执行', icon: 'success' })
      this.fetchChecklist()
    } catch (err) {
      wx.showToast({ title: err.message || TOAST_TEXT.OPERATE_FAILED, icon: 'none' })
    }
  }
})
