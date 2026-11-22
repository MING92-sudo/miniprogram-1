const fs = require('fs')
const path = require('path')
const subsetFont = require('subset-font')
async function main() {
  const full = fs.readFileSync(path.join(__dirname, '../tmp/ZCOOLQingKeHuangYou-Regular.ttf'))
  const text = fs.readFileSync(path.join(__dirname, 'required.txt'), 'utf8')
  const out = await subsetFont(full, text, { targetFormat: 'truetype' })
  fs.writeFileSync(path.join(__dirname, '../../assets/fonts/zcool-subset.ttf'), out)
  console.log(JSON.stringify({ subsetBytes: out.length }))
}
main()