// 状态机→语义色映射（spec §2.5）；tagClass 对齐原型 statusTag（中文态 + 枚举码）
const SIGNAL = ['TRAPPED', 'OVERDUE', 'URGENT', 'FAULT', 'REJECTED', 'VOID', 'SCRAPPED', 'FAILED', 'BLOCKED', 'REFUSED_RETURNED']
const OK = ['ARCHIVED', 'SIGNED', 'CLOSED', 'DONE', 'NORMAL', 'ACTIVE', 'CONFIRMED', 'REPORTED', 'SUCCESS', 'PASS', 'QUALIFIED', 'EFFECTIVE']
const AMBER = ['PROCESSING', 'PENDING', 'IN_PROGRESS', 'ASSIGNED', 'REVIEWING', 'AMENDING', 'SIGNING', 'INSPECTING', 'RECTIFYING', 'DOC_SUBMITTING', 'PENDING_CONFIRM']
export function statusColor(status) {
  const s = String(status || '').toUpperCase()
  if (SIGNAL.includes(s)) return { bg: 'var(--signal)', fg: 'var(--signal)' }
  if (OK.includes(s)) return { bg: 'var(--ok)', fg: 'var(--ok)' }
  if (AMBER.includes(s)) return { bg: 'var(--amber)', fg: 'var(--amber)' }
  return { bg: 'var(--muted)', fg: 'var(--muted)' }
}

// 原型 statusTag 中文态映射（app.js statusTag + 记录/移交/工单状态集扩展）
const CN_TONE = {
  正常: 'ok', 在保: 'ok', 履行中: 'ok', 有效: 'ok', 生效中: 'ok', 已归档: 'ok', 已签署: 'ok', 已闭环: 'ok', 已生效: 'ok',
  合格: 'ok', 成功: 'ok', 已评价: 'ok', 启用: 'ok', 已重试: 'ok', 已完成: 'ok', 已确认: 'ok', 已批准: 'ok', 通过: 'ok',
  待激活: 'amber', 待接管: 'amber', 临期: 'amber', 待续签: 'amber', 待生效: 'amber', 待安装部审核: 'amber', 待维保接收: 'amber',
  验收中: 'amber', 待双方签字: 'amber', 待维保签字: 'amber', 待复核: 'amber', 待使用单位签署: 'amber', 待配合人签字: 'amber',
  作业中: 'amber', 待接单: 'amber', 已接单: 'amber', 处置中: 'amber', 待使用单位确认: 'amber', 待整改: 'amber', 待评价: 'amber',
  上报中: 'amber', 待上报: 'amber', 修订中: 'amber', 待处理: 'amber', 进行中: 'amber', 已派单: 'amber', 查验中: 'amber',
  整改中: 'amber', 资料提交: 'amber', 待确认: 'amber', 资料收集: 'amber', 接管查验: 'amber', 待签字: 'amber', 紧急: 'amber',
  超时: 'signal', 困人: 'signal', 失败: 'signal', 双签违规: 'signal', 退回整改: 'signal', 被拦截: 'signal', 已拦截: 'signal',
  上报失败: 'signal', 已驳回: 'signal', 不合格: 'signal', 故障: 'signal', 已拒签退回: 'signal',
  停用: 'dim', 离职: 'dim', 已到期: 'dim', 暂停: 'dim', 已作废: 'dim', 已使用: 'dim', 已过期: 'dim', 已撤回: 'dim', 已回收: 'dim',
  已取消: 'dim', 已结算: 'dim', 报废: 'dim', 移装: 'dim', 已关闭: 'dim', 不适用: 'dim'
}
// 返回原型 .tag 语义色类名后缀：ok / amber / signal / dim / ghost
export function tagClass(status) {
  const s = String(status || '')
  if (CN_TONE[s]) return CN_TONE[s]
  const u = s.toUpperCase()
  if (SIGNAL.includes(u)) return 'signal'
  if (OK.includes(u)) return 'ok'
  if (AMBER.includes(u)) return 'amber'
  if (['DISABLED', 'INACTIVE', 'CANCELLED', 'EXPIRED', 'REVOKED', 'NA'].includes(u)) return 'dim'
  return 'ghost'
}