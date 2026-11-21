'use strict'
/**
 * E2E 种子基线：账号口令与演示数据 ID。
 * 与 DemoDataSeeder 对齐；数据库重建后若 ID 变化，只需改这里。
 */

const accounts = {
  // 平台/管理
  admin: { phone: '13800000009', password: '123456', role: 'ADMIN' },
  // 维保
  maintMgr: { phone: '13800000015', password: '123456', role: 'MAINT_MGR' },
  maintTech: { phone: '13800000011', password: '123456', role: 'MAINT_TECH' },
  leader: { phone: '13800000002', password: '123456', role: 'LEADER' },
  worker: { phone: '13800000001', password: '123456', role: 'WORKER' },
  worker2: { phone: '13800000004', password: '123456', role: 'WORKER' },
  // 安装
  instMgr: { phone: '13800000099', password: '123456', role: 'INST_MGR' },
  instPm: { phone: '13800000013', password: '123456', role: 'INST_PM' },
  instClerk: { phone: '13800000014', password: '123456', role: 'INST_CLERK' },
  installer: { phone: '13800000017', password: '123456', role: 'INSTALLER' },
  // 客户（client_account）
  clientAdmin: { phone: '13800000003', password: '123456' },
  clientSigner: { phone: '13800000012', password: '123456' },
  clientSigner2: { phone: '13800000018', password: '123456' },
  clientOther: { phone: '13800003005', password: '123456' }
}

const seed = {
  company: 'org_company',
  orgs: {
    company: 'org_company',
    deptInstall: 'org_dept_install',
    deptMaint: 'org_dept_maint',
    teamInstall1: 'org_team_install_1',
    teamMaint1: 'org_team_maint_1',
    teamMaint2: 'org_team_maint_2'
  },
  employees: {
    admin: 'emp_admin',
    maintMgr: 'emp_zjl',
    maintTech: 'emp_lzg',
    leader: 'emp_2',
    worker: 'emp_1',
    worker2: 'emp_3',
    instMgr: 'emp_zhang',
    instPm: 'emp_wq',
    instClerk: 'emp_lq',
    installer: 'emp_azy'
  },
  useUnits: { uu1: 'uu_1', uu2: 'uu_2' },
  clientAccounts: { uu1Admin: 'ca_1', uu1Signer: 'ca_2', uu1Signer2: 'ca_3', uu2Admin: 'ca_4' },
  elevators: { el1: 'el_1', el2: 'el_2', el3: 'el_3', el4: 'el_4' },
  projects: { pj1: 'pj_new4PWK9Que9A2uLMRDuXQ' },
  contracts: { ct1: 'ct_zXEnP33hQ6emr_xaJ6QKPA' },
  handovers: { ho1: 'ho_l0Ea-tO6SkGqIPioRtyaYA' },
  handoverRects: { hr1: 'hr_p8-l7GtDQpeXrtDeEvNspg' },
  records: { ur1: 'ur_1', ur2: 'ur_2' },
  workOrders: { wo1: 'wo_1', wo2: 'wo_2' },
  faults: { ft1: 'ft_1' },
  regions: { rg1: 'rg_7X5BrvaLTAeWFTNT2JbgkA' },
  invites: { iv1: 'iv_7Fc6vlvaQh63hOMpoja-sA' },
  grants: { gr1: 'gr_AldXCk5_QQOUGsSdUyO02A' },
  messages: { msg1: 'msg_1' },
  templates: { tpl1: '1' },
  rescues: { rs1: 'rs_1' },
  drills: { dr1: 'dr_1' },
  inspects: { in1: 'in_1' },
  approvals: { ea1: 'ea_jR3TQDtORYKzVMK-GuMvKw' },
  knowledge: { kb0: 'kb_0' },
  files: { f1: '' }
}

module.exports = { accounts, seed }
