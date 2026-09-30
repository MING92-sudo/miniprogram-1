// 校验 TSG T5002—2017 附件A—D 检查项模板数量、编码与关键项规则
const {
  APPENDIX_TPLS,
  FREQ_CHAIN,
  CATEGORY_APPENDIX
} = require('../mock/checklist-template')
const { buildChecklist } = require('../mock/data')

const FREQS = ['HALF', 'QUARTER', 'HALF_YEAR', 'YEAR']
const EXPECTED_ROWS = {
  A: { HALF: 31, QUARTER: 13, HALF_YEAR: 15, YEAR: 17 },
  B: { HALF: 30, QUARTER: 13, HALF_YEAR: 9, YEAR: 14 },
  C: { HALF: 20, QUARTER: 8, HALF_YEAR: 10, YEAR: 14 },
  D: { HALF: 33, QUARTER: 5, HALF_YEAR: 12, YEAR: 13 }
}
const EXPECTED_TOTAL = 257

function annualRows(appendix) {
  return FREQ_CHAIN.OY.reduce(function (rows, freq) {
    return rows.concat(APPENDIX_TPLS[appendix][freq])
  }, [])
}

let failed = false
let total = 0
Object.keys(EXPECTED_ROWS).forEach(function (appendix) {
  FREQS.forEach(function (freq) {
    const rows = APPENDIX_TPLS[appendix][freq]
    const expected = EXPECTED_ROWS[appendix][freq]
    total += rows.length
    if (rows.length !== expected) {
      console.error(appendix, freq, '数量错误:', rows.length, '应', expected)
      failed = true
    }
    rows.forEach(function (row) {
      const code = appendix + '-' + (FREQS.indexOf(freq) + 1) + '-' + String(row.seq).padStart(2, '0')
      if (row.itemCode !== code) {
        console.error('编码错误:', row.itemCode, '应', code)
        failed = true
      }
      if (row.isKey !== row.photoRequired) {
        console.error('关键项/拍照要求不一致:', row.itemCode)
        failed = true
      }
      if (!row.name || !row.requirement || !row.judgeType) {
        console.error('字段缺失:', row.itemCode)
        failed = true
      }
    })
  })
})

if (total !== EXPECTED_TOTAL) {
  console.error('总行数错误:', total, '应', EXPECTED_TOTAL)
  failed = true
}

// 品种路由：确保不能把液压/杂物/扶梯模板误落到附件A。
const routeCases = [
  ['曳引驱动电梯', 'A', 76],
  ['液压乘客电梯', 'B', 66],
  ['杂物电梯', 'C', 52],
  ['自动扶梯', 'D', 63]
]
routeCases.forEach(function (c) {
  const category = c[0]
  const appendix = c[1]
  const expected = c[2]
  const rows = buildChecklist('OY', category)
  if (CATEGORY_APPENDIX[category] !== appendix || rows.length !== expected || rows[0].itemCode.charAt(0) !== appendix) {
    console.error('品种路由错误:', category, rows.length, rows[0] && rows[0].itemCode)
    failed = true
  }
})

console.log('附件A:', annualRows('A').length, '项 | 附件B:', annualRows('B').length, '项 | 附件C:', annualRows('C').length, '项 | 附件D:', annualRows('D').length, '项')
console.log('模板总行数(应=257):', total)
if (failed) process.exit(1)
