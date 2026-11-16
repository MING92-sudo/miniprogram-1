// 图标 key → 本地 iconfont 类映射（M2，裁决 R2：仅用 assets/icons/icons.wxss 既有 64 类，零外链）
// 蓝图 icon key 与 FA 原名不一致处按语义最近映射；守卫 verify-miniapp-routes.js icons-map 锁定。
const ICONS = {
  gauge: 'i-gauge-high',
  clipboard: 'i-clipboard-list',
  elevator: 'i-elevator',
  user: 'i-user',
  home: 'i-house',
  'file-signature': 'i-file-signature',
  folder: 'i-book',
  building: 'i-building',
  'file-check': 'i-file-circle-check',
  chart: 'i-chart-line',
  'file-text': 'i-file-pdf',
  users: 'i-user-tie',
  shield: 'i-shield-halved',
  settings: 'i-gear',
  qrcode: 'i-qrcode',
  bolt: 'i-bolt',
  wrench: 'i-screwdriver-wrench',
  bullhorn: 'i-bullhorn',
  'calendar-check-r': 'i-calendar-check-r'
}

module.exports = { ICONS }