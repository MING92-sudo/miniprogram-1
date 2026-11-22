const fs = require('fs')
const path = require('path')
const SVGIcons2SVGFontStream = require('svgicons2svgfont')
const svg2ttf = require('svg2ttf')
const ttf2woff2 = require('ttf2woff2')
async function main() {
  const svgDir = path.join(__dirname, 'svg')
  const files = fs.readdirSync(svgDir).filter(f => f.endsWith('.svg')).sort()
  const fontStream = new SVGIcons2SVGFontStream({ fontName: 'bwicons', normalize: true, fontHeight: 1000, log: () => {} })
  let svgFont = ''
  fontStream.on('data', d => { svgFont += d.toString() })
  const done = new Promise((res, rej) => { fontStream.on('end', res); fontStream.on('error', rej) })
  const codepoints = {}
  files.forEach((f, i) => {
    const name = f.replace(/\.svg$/, '')
    codepoints[name] = 0xF101 + i
    const contents = fs.createReadStream(path.join(svgDir, f))
    contents.metadata = { name, unicode: [String.fromCodePoint(0xF101 + i)] }
    fontStream.write(contents)
  })
  fontStream.end()
  await done
  const ttf = svg2ttf(svgFont, {})
  const woff2 = ttf2woff2(Buffer.from(ttf.buffer))
  let css = '/* 博威无纸化图标 · Font Awesome Free 6.5.2 子集 · CC BY 4.0（署名见「我的」页） */\n'
  css += "@font-face{font-family:'bwicons';src:url(data:font/woff2;base64," + woff2.toString('base64') + ") format('woff2');}\n"
  for (const [name, cp] of Object.entries(codepoints)) {
    css += '.' + name + ':before{font-family:\'bwicons\';font-style:normal;content:"\\' + cp.toString(16) + '";}\n'
  }
  const out = path.join(__dirname, '../../assets/icons')
  fs.mkdirSync(out, { recursive: true })
  fs.writeFileSync(path.join(out, 'icons.wxss'), css)
  console.log(JSON.stringify({ glyphs: Object.keys(codepoints).length, woff2Bytes: woff2.length }))
}
main()