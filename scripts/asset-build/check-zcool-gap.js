// scripts/asset-build/check-zcool-gap.js
// 扩字后 TTF 子集 cmap 缺口检测（opentype.js 仅解析 TTF/OTF，不解 WOFF2）
const fs = require('fs')
const path = require('path')
const opentype = require('opentype.js')
const dir = path.join(__dirname, '../../assets/fonts')
const buf = fs.readFileSync(path.join(dir, 'zcool-subset.ttf'))
const font = opentype.parse(buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength))
const required = [...new Set(fs.readFileSync(path.join(__dirname, 'required.txt'), 'utf8'))]
const missing = required.filter(ch => !font.charToGlyphIndex(ch))
fs.writeFileSync(path.join(dir, 'zcool-gap.json'), JSON.stringify({ required: required.length, missing }, null, 2))
console.log(JSON.stringify({ required: required.length, missingCount: missing.length, missing: missing.join('') }))
if (missing.length) process.exit(1)