// 记录单/双人签字页纯函数投影（无 wx 依赖，scripts/verify-review-fixes.js 行为断言）：结论必须反映异常项（不得虚报合格）；
const { RECORD_TEXT } = require('../../constants/index')

/** fixed 恒 0：记录视图暂无逐项整改闭环字段，不得虚报「异常已整改」。 */
function buildRecordSummary(executed) {
  const items = executed || []
  const ok = items.filter((i) => i.result === 'NORMAL').length
  const abItems = items.filter((i) => i.result === 'ABNORMAL')
  const gmap = {}
  const groups = []
  items.forEach((i) => {
    const g = i.freqLabel || '其他'
    if (!gmap[g]) { gmap[g] = { label: g, total: 0, ab: 0 }; groups.push(gmap[g]) }
    gmap[g].total += 1
    if (i.result === 'ABNORMAL') gmap[g].ab += 1
  })
  return {
    stats: { total: items.length, ok: ok, fixed: 0, left: abItems.length },
    groups: groups,
    abnormalDesc: abItems.map((i) => (i.name || '') + (i.abnormalDesc ? '（' + i.abnormalDesc + '）' : '')).join('、'),
    conclusion: abItems.length === 0 ? RECORD_TEXT.CONCLUSION_OK : RECORD_TEXT.CONCLUSION_ABNORMAL
  }
}

/** URL 仅存在于签退后 recordInfo，布尔为准）。 */
function buildSignState(order, me) {
  const o = order || {}
  const u = me || {}
  const isDual = !!o.assistantName
  const isPrincipal = !!o.workerName && (u.name === o.workerName || (!!o.workerPlatformId && u.platformId === o.workerPlatformId))
  const isAssistant = !!o.assistantName && (u.name === o.assistantName || (!!o.assistantPlatformId && u.platformId === o.assistantPlatformId))
  const workerSigned = !!(o.workerSigned || o.workerSignatureUrl)
  const assistantSigned = !!(o.assistantSigned || o.assistantSignatureUrl)
  const mySigned = (isPrincipal && workerSigned) || (isAssistant && assistantSigned)
  const bothSigned = workerSigned && (!isDual || assistantSigned)
  return {
    isDual: isDual,
    isPrincipal: isPrincipal,
    isAssistant: isAssistant,
    workerSigned: workerSigned,
    assistantSigned: assistantSigned,
    workerSignText: workerSigned ? RECORD_TEXT.SIGNED : RECORD_TEXT.UNSIGNED,
    assistantSignText: assistantSigned ? RECORD_TEXT.SIGNED : RECORD_TEXT.UNSIGNED,
    mySigned: mySigned,
    bothSigned: bothSigned,
    primaryText: mySigned && bothSigned ? RECORD_TEXT.VIEW_RECORD : RECORD_TEXT.SIGN_SELF,
    primaryDisabled: !isPrincipal && !isAssistant,
    myRoleText: isPrincipal ? RECORD_TEXT.ROLE_MAIN : isAssistant ? RECORD_TEXT.ROLE_ASSIST : RECORD_TEXT.ROLE_NONE
  }
}

module.exports = { buildRecordSummary, buildSignState }