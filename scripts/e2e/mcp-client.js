// token 运行时从 DevTools 本地 mcp.json 读取，严禁写入代码/入库。
const { spawn } = require('child_process')
const fs = require('fs')
const path = require('path')
const os = require('os')

function findToken() {
  const base = path.join(process.env.LOCALAPPDATA, '微信开发者工具', 'User Data')
  for (const d of fs.readdirSync(base)) {
    const p = path.join(base, d, 'Default', 'mcp.json')
    if (fs.existsSync(p)) {
      const j = JSON.parse(fs.readFileSync(p, 'utf8'))
      const args = (j.mcpServers && j.mcpServers['wechat-devtools'] && j.mcpServers['wechat-devtools'].args) || []
      const t = args.find((a) => a.startsWith('--token='))
      if (t) return t.slice(8)
    }
  }
  throw new Error('mcp.json token not found')
}

async function main() {
  const mode = process.argv[2] || 'list'
  const tool = process.argv[3]
  const rawArg = process.argv[4]; const args = rawArg ? JSON.parse(rawArg.startsWith('@') ? fs.readFileSync(rawArg.slice(1), 'utf8') : rawArg) : {}
  const child = spawn('wechatide.cmd', ['mcp', '--token=' + findToken()], { shell: true, stdio: ['pipe', 'pipe', 'pipe'] })
  let buf = ''
  const pending = new Map()
  let nextId = 1
  child.stdout.on('data', (d) => {
    buf += d.toString()
    let i
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim()
      buf = buf.slice(i + 1)
      if (!line) continue
      try {
        const msg = JSON.parse(line)
        if (process.env.MCP_DEBUG) console.error('RECV ' + line.slice(0, 400)); if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
      } catch (e) { /* non-JSON log line */ }
    }
  })
  child.stderr.on('data', () => {})
  function send(method, params) {
    const id = nextId++
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { pending.delete(id); reject(new Error('timeout ' + method)) }, 120000)
      pending.set(id, (m) => { clearTimeout(t); resolve(m) })
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n')
    })
  }
  function notify(method, params) { child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n') }
  try {
    await send('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'codex-e2e', version: '1.0.0' } })
    notify('notifications/initialized', {})
    if (mode === 'list') {
      const r = await send('tools/list', {})
      console.log(JSON.stringify(r.result, null, 2))
    } else if (mode === 'call') {
      const r = await send('tools/call', { name: tool, arguments: args })
      console.log(JSON.stringify(r.result || r.error, null, 2))
    }
  } finally { child.kill() }
}
main().catch((e) => { console.error('ERR ' + e.message); process.exit(1) })



