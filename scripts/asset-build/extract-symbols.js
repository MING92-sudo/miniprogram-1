// scripts/asset-build/extract-symbols.js
// 从原型 index.html 提取 64 个 Font Awesome symbol → 独立 SVG（M0 资产管线）
const fs = require('fs')
const path = require('path')
const html = fs.readFileSync('C:/Users/18223/Desktop/UI/index.html', 'utf8')
const outDir = path.join(__dirname, 'svg')
fs.mkdirSync(outDir, { recursive: true })
let n = 0
for (const m of html.matchAll(/<symbol id="(i-[^"]+)" viewBox="0 0 512 512">([\s\S]*?)<\/symbol>/g)) {
  fs.writeFileSync(path.join(outDir, m[1] + '.svg'),
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512">' + m[2] + '</svg>')
  n++
}
console.log(JSON.stringify({ symbols: n }))
if (n !== 64) process.exit(1)