// 护栏：<text> 内禁止嵌套自定义组件（text 只支持嵌套 text，组件静默不渲染——电梯档案 mask-field 空白根因，2026-11-25）
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..')

const componentTags = fs.readdirSync(path.join(ROOT, 'components'))
  .filter((d) => fs.existsSync(path.join(ROOT, 'components', d, 'index.wxml')))

const wxmlFiles = []
function walk(dir) {
  if (!fs.existsSync(dir)) return
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name)
    if (fs.statSync(p).isDirectory()) walk(p)
    else if (name.endsWith('.wxml')) wxmlFiles.push(p)
  }
}
walk(path.join(ROOT, 'pages'))
walk(path.join(ROOT, 'pkg-handover'))
walk(path.join(ROOT, 'pkg-maintenance'))
walk(path.join(ROOT, 'pkg-client'))
walk(path.join(ROOT, 'pkg-admin'))
walk(path.join(ROOT, 'components'))

const tagRe = new RegExp('<(' + componentTags.join('|') + ')[\\s>]', 'g')
const textBlockRe = /<text\b[^>]*>([\s\S]*?)<\/text>/g
const violations = []
for (const file of wxmlFiles) {
  const src = fs.readFileSync(file, 'utf8')
  let m
  while ((m = textBlockRe.exec(src))) {
    tagRe.lastIndex = 0
    let t
    while ((t = tagRe.exec(m[1]))) {
      const line = src.slice(0, m.index).split('\n').length
      violations.push(path.relative(ROOT, file) + ':' + line + ' <text> 内嵌套 <' + t[1] + '>')
    }
  }
}

if (violations.length) {
  console.error('FAIL <text> 内嵌套自定义组件（真机/模拟器均不渲染）：')
  violations.forEach((v) => console.error('  ' + v))
  process.exit(1)
}
console.log('PASS wxml-text-nesting：' + wxmlFiles.length + ' 个 wxml 检查通过（' + componentTags.length + ' 个组件标签）')
