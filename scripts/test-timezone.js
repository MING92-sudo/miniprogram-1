// 时区回归：设备时区（TZ）不得影响 GMT+8 文本输出与解析结果。
const assert = require('assert')
const path = require('path')
const { execFileSync } = require('child_process')

const utilPath = path.resolve(__dirname, '../utils/util.js')
const { parseTime } = require(utilPath)

if (process.argv[2] === '--probe') {
  const { formatTime, parseTime } = require(utilPath)
  const instant = Date.UTC(2026, 0, 2, 3, 4, 5)
  process.stdout.write(JSON.stringify({
    fromDateObject: formatTime(new Date(instant)),
    fromMillis: formatTime(instant),
    fromText: formatTime('2026-01-02 11:04:05'),
    fromDateOnly: formatTime('2026-01-02'),
    fromIso: formatTime('2026-01-02T03:04:05Z'),
    parsedText: parseTime('2026-01-02 11:04:05'),
    parsedDateOnly: parseTime('2026-01-02'),
    parsedIso: parseTime('2026-01-02T03:04:05Z'),
    invalidText: formatTime('not a date'),
    invalidParse: String(parseTime('not a date')),
    explicitZero: formatTime(0),
    explicitEmpty: formatTime(''),
    explicitNull: formatTime(null),
    invalidCalendar: formatTime('2026-02-31'),
    invalidCalendarParse: String(parseTime('2026-02-31')),
    noArgument: formatTime()
  }))
  process.exit(0)
}

function probe(tz) {
  return JSON.parse(execFileSync(process.execPath, [__filename, '--probe'], {
    env: Object.assign({}, process.env, { TZ: tz }),
    encoding: 'utf8'
  }))
}

const utc = probe('UTC')
const shanghai = probe('Asia/Shanghai')

assert.deepStrictEqual(utc, shanghai, '同一时刻在 UTC 与 Asia/Shanghai 下输出或解析不一致')

const expected = '2026-01-02 11:04:05'
const instant = Date.UTC(2026, 0, 2, 3, 4, 5)
assert.strictEqual(utc.fromDateObject, expected)
assert.strictEqual(utc.fromMillis, expected)
assert.strictEqual(utc.fromText, expected)
assert.strictEqual(utc.fromDateOnly, '2026-01-02 00:00:00')
assert.strictEqual(utc.fromIso, expected)
assert.strictEqual(utc.parsedText, instant)
assert.strictEqual(utc.parsedDateOnly, Date.UTC(2026, 0, 1, 16, 0, 0))
assert.strictEqual(utc.parsedIso, instant)
assert.strictEqual(utc.invalidText, '', '非法时间应返回空串而不是 NaN 文本')
assert.strictEqual(utc.invalidParse, 'NaN')
assert.strictEqual(utc.explicitZero, '', '显式 0 不得伪造成当前时间或 1970 时间')
assert.strictEqual(utc.explicitEmpty, '', '显式空串不得表示当前时间')
assert.strictEqual(utc.explicitNull, '', '显式 null 不得表示当前时间')
assert.strictEqual(utc.invalidCalendar, '', '非法日历日期不得被静默归一')
assert.strictEqual(utc.invalidCalendarParse, 'NaN', '非法日历日期解析必须为 NaN')
assert(Math.abs(Date.now() - parseTime(utc.noArgument)) < 5000, '无参数仍须表示当前时间')
console.log('PASS 固定 GMT+8：设备时区不影响格式化与解析')
