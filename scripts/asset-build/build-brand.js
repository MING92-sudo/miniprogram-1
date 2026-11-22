const fs = require('fs')
const path = require('path')
const ROOT = path.join(__dirname, '..', '..')
const png = fs.readFileSync(path.join(ROOT, 'assets/brand/logo-mark.png'))
const b64 = png.toString('base64')
const css = '/* 博威无纸化圆形徽标（透明底裁切件，BOWEI 字母不上页）· 由 scripts/asset-build/build-brand.js 生成，勿手改 */\n' +
  '.brand-mark{background-image:url(data:image/png;base64,' + b64 + ');' +
  'background-size:contain;background-position:center;background-repeat:no-repeat;}\n'
fs.writeFileSync(path.join(ROOT, 'styles/brand.wxss'), css)
console.log(JSON.stringify({ pngBytes: png.length, base64Chars: b64.length }))