#!/bin/bash
# 小程序自动化巡检入口（多角色）：启动 IDE → 开自动化 → 逐角色跑链路
#
# 用法：
#   export WX_DEVTOOLS_TOKEN=<IDE 安全设置里的 CLI/自动化 token>
#   bash scripts/e2e/mp-automate.sh [--check] 13800000003 13800000011
#
# 说明：
# 1) IDE 是 CLI 的子进程，命令结束即被回收，所以「启动 + 巡检」必须在同一次调用内完成；
# 2) 巡检前请确认 config/index.js 的 ENV 与后端一致（dev=本地 8080，prod=云托管）；
# 3) token 不落库：只从环境变量读取。
set -u
: "${WX_DEVTOOLS_TOKEN:?需要先 export WX_DEVTOOLS_TOKEN=<IDE 自动化 token>}"

PORT="${WX_IDE_PORT:-39882}"
AUTO="${WX_AUTO_PORT:-9420}"
IDE_DIR="${WX_IDE_DIR:-/c/Program Files (x86)/Tencent/微信web开发者工具}"
PROJ="${WX_PROJECT:-C://Users//18223//Desktop//WXapp//miniprogram-1}"

cd "$IDE_DIR" || { echo "找不到 IDE 目录：$IDE_DIR"; exit 2; }
./cli.bat open --project "$PROJ" --port "$PORT" --token "$WX_DEVTOOLS_TOKEN" >/dev/null 2>&1
sleep 8
./cli.bat auto --project "$PROJ" --auto-port "$AUTO" --port "$PORT" --token "$WX_DEVTOOLS_TOKEN" >/dev/null 2>&1
sleep 5

cd "$(dirname "$0")" || exit 2
if [ "${1:-}" = "--check" ]; then
  node mp-client-endpoints.js
  exit $?
fi

for phone in "$@"; do
  echo "############ $phone ############"
  node mp-bugs-3.js "$AUTO" "$phone" 123456 "$phone" 2>&1 | tail -20
done
