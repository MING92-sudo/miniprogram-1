// 旧图标名保留别名兼容。
const ALIAS = {
  dashboard: 'gauge', org: 'sitemap', ledger: 'folder', handover: 'exchange', plan: 'calendar',
  dispatch: 'bolt', stats: 'chart', audit: 'history', settings: 'sliders', compliance: 'shield',
  search: 'search', close: 'x', check: 'check', warn: 'alert', chevron: 'chevron-right', default: 'list'
}
export const ICON_NAMES = ['gauge', 'sitemap', 'users', 'user', 'shield', 'key', 'building', 'file-text', 'file-sign', 'file-check', 'elevator', 'exchange', 'calendar', 'clipboard', 'bolt', 'inbox', 'chart', 'pie', 'history', 'sliders', 'palette', 'search', 'filter', 'sort', 'chevron-down', 'chevron-right', 'chevron-left', 'arrow-right', 'plus', 'minus', 'check', 'x', 'alert', 'clock', 'download', 'upload', 'print', 'qr', 'edit', 'eye', 'eye-off', 'lock', 'hash', 'signature', 'external', 'logout', 'panel', 'refresh', 'phone', 'mail', 'info', 'link', 'image', 'ban', 'tag', 'wrench', 'bell', 'activity', 'database', 'list', 'layers', 'map-pin', 'user-check', 'user-x', 'folder', 'weixin', 'clipboard-list', 'archive', 'cloud-upload', 'star', 'message', 'monitor']
const SIZE_CLS = { 13: 'ic-sm', 20: 'ic-lg', 26: 'ic-xl' }
export function icon(name, clsOrSize) {
  const n = ALIAS[name] || name || 'list'
  const cls = typeof clsOrSize === 'number' ? (SIZE_CLS[clsOrSize] || '') : (clsOrSize || '')
  return `<svg class="ic${cls ? ' ' + cls : ''}" aria-hidden="true"><use href="#i-${n}"></use></svg>`
}