// M2 状态机→语义色映射（spec §2.5）；M4 逐态对齐
const SIGNAL = ['TRAPPED', 'OVERDUE', 'URGENT', 'FAULT', 'REJECTED', 'VOID', 'SCRAPPED']
const OK = ['ARCHIVED', 'SIGNED', 'CLOSED', 'DONE', 'NORMAL', 'ACTIVE', 'CONFIRMED', 'REPORTED']
const AMBER = ['PROCESSING', 'PENDING', 'IN_PROGRESS', 'ASSIGNED', 'REVIEWING', 'AMENDING']
export function statusColor(status) {
  const s = String(status || '').toUpperCase()
  if (SIGNAL.includes(s)) return { bg: 'var(--signal)', fg: 'var(--signal)' }
  if (OK.includes(s)) return { bg: 'var(--ok)', fg: 'var(--ok)' }
  if (AMBER.includes(s)) return { bg: 'var(--amber)', fg: 'var(--amber)' }
  return { bg: 'var(--muted)', fg: 'var(--muted)' }
}