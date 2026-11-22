const fs = require('fs')
const path = require('path')
const ttf2woff2 = require('ttf2woff2')
const ROOT = path.join(__dirname, '..', '..')
const ttf = fs.readFileSync(path.join(ROOT, 'assets/fonts/zcool-subset.ttf'))
const woff2 = ttf2woff2(ttf)
const b64 = Buffer.from(woff2).toString('base64')
const css = '/* 博威无纸化显示字 · ZCOOL QingKe HuangYou 扩字子集（135 字）· OFL 1.1（署名见「我的」页）\n   由 scripts/asset-build/build-fonts.js 生成，勿手改 */\n' +
  "@font-face{font-family:'ZCOOL QingKe HuangYou';font-style:normal;font-weight:400;font-display:swap;" +
  "src:url('data:font/woff2;base64," + b64 + "') format('woff2');}\n"
fs.writeFileSync(path.join(ROOT, 'styles/fonts.wxss'), css)
// 自校验：base64 解码字节数 = woff2 字节数
const back = Buffer.from(b64, 'base64')
console.log(JSON.stringify({ ttfBytes: ttf.length, woff2Bytes: woff2.length, base64Chars: b64.length, roundtrip: back.length === woff2.length }))