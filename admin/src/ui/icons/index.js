// M2 inline SVG 图标（D4 离线，无外部请求）；路径取自原型 icons.svg
const PATHS = {
  dashboard: 'M3 3h8v8H3zM13 3h8v5h-8zM13 10h8v11h-8zM3 13h8v8H3z',
  org: 'M12 3l9 5-9 5-9-5zM3 13l9 5 9-5',
  ledger: 'M4 4h16v16H4zM4 9h16M9 9v11',
  handover: 'M4 12l5 5L20 6',
  plan: 'M4 5h16M4 12h16M4 19h10',
  dispatch: 'M3 12h13M12 5l7 7-7 7',
  stats: 'M4 20V10M10 20V4M16 20v-7M22 20H2',
  audit: 'M6 3h9l5 5v13H6zM14 3v6h6',
  settings: 'M12 8a4 4 0 100 8 4 4 0 000-8zM12 2v3M12 19v3M2 12h3M19 12h3',
  compliance: 'M12 3l8 4v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z',
  search: 'M10 4a6 6 0 100 12 6 6 0 000-12zM20 20l-5-5',
  close: 'M5 5l14 14M19 5L5 19',
  check: 'M4 12l5 5L20 6',
  warn: 'M12 3l10 18H2zM12 10v5M12 18v.5',
  chevron: 'M9 6l6 6-6 6',
  default: 'M4 4h16v16H4z'
}
export function icon(name, size = 16) {
  const d = PATHS[name] || PATHS.default
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${d}"/></svg>`
}
export const ICON_NAMES = Object.keys(PATHS)