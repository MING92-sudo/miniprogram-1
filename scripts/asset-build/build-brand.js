// 品牌徽标内嵌：assets/brand/logo.jpg → base64 → styles/brand.wxss（M2，圆形裁切口径见 M0 §6）
// 裁切：background-size:100% auto + 元素高=宽×335/450，仅取圆形徽标区（BOWEI 字母区 y≈360–430 不渲染）
const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..', '..')
const jpg = fs.readFileSync(path.join(ROOT, 'assets/brand/logo.jpg'))
const b64 = jpg.toString('base64')
const css = '/* 博威无纸化圆形徽标（裁切件，BOWEI 字母不上页）· 由 scripts/asset-build/build-brand.js 生成，勿手改 */\n' +
  '.brand-mark{background-image:url(data:image/jpeg;base64,' + b64 + ');' +
  'background-size:100% auto;background-position:top;background-repeat:no-repeat;}\n'
fs.writeFileSync(path.join(ROOT, 'styles/brand.wxss'), css)
console.log(JSON.stringify({ jpgBytes: jpg.length, base64Chars: b64.length, cropRatio: 335 / 450 }))