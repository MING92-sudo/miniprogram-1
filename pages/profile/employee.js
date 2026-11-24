const auth = require('../../services/auth')
const { definePage } = require('../../utils/guard')
const { ROUTES } = require('../../config/routes')
const { PROFILE_TEXT, DEPT_TEXT, WORK_STAT_TEXT, SYNC_STATUS_TEXT } = require('../../constants/index')

function maskPhone(p) {
  const s = String(p || '')
  if (s.length < 7) return s
  return s.slice(0, 3) + '****' + s.slice(-4)
}

definePage(ROUTES['/pages/profile/employee'], {
  data: { userInfo: null, avatarChar: '', rows: [], text: PROFILE_TEXT },
  onLoad() { this.load() },
  async load() {
    const g = getApp().globalData || {}
    let u = g.userInfo || {}
    try {
      const res = await auth.me()
      if (res && res.userInfo) u = res.userInfo
    } catch (e) { /* 离线/接口失败回退会话缓存 */ }
    this.setData({ userInfo: u, avatarChar: (u.name || '').charAt(0) || '我', rows: this.buildRows(u, g.scope || '') })
  },
  buildRows(u, scope) {
    const t = PROFILE_TEXT
    const contract = (u.workStartDate || u.workEndDate) ? (u.workStartDate || '?') + ' ~ ' + (u.workEndDate || '?') : ''
    return [
      { label: t.EMP_PHONE, value: maskPhone(u.phone || u.account) || '-' },
      { label: t.EMP_ACCOUNT, value: u.account || '-' },
      { label: t.EMP_JOB, value: u.roleText || u.jobTitle || '-' },
      { label: t.EMP_DEPT, value: DEPT_TEXT[u.department] || u.department || '-' },
      { label: t.EMP_GROUP, value: u.groupName || '-' },
      { label: t.EMP_PLATFORM_ID, value: u.platformId || '-' },
      { label: t.EMP_CERT, value: u.certificate || '-' },
      { label: t.EMP_CONTRACT, value: contract || '-' },
      { label: t.EMP_WORK_STAT, value: WORK_STAT_TEXT[u.workStat] || u.workStat || '-' },
      { label: t.EMP_SYNC, value: SYNC_STATUS_TEXT[u.syncStatus] || u.syncStatus || '-' },
      { label: t.EMP_SCOPE, value: scope || '-' }
    ]
  }
})
