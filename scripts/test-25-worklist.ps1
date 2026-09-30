# ============================================================
# test-25-worklist.ps1 - 2.2 主体查询 + 2.5 单位维保人员查询 实测
#
# Usage  : powershell -ExecutionPolicy Bypass -File .\scripts\test-25-worklist.ps1 [-OrgCode 91500000MAEL02JM8M]
# Creds  : 自动加载项目根 .env（REG_USERNAME/KEY/APPCODE/SECRET，绝不打印）
# 说明   : 2.5 查询范围由 token 所属单位决定（入参仅 changState/workEndDate）；
#          先用 2.2 按 organizationCode 查主体确认单位身份，再拉人员列表。
# ============================================================
param(
    [string]$OrgCode = '91500000MAEL02JM8M'
)

$ErrorActionPreference = 'Continue'
$root    = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*(REG_[A-Z_]+)\s*=\s*(.*)\s*$') {
            Set-Item -Path ("Env:" + $Matches[1]) -Value $Matches[2].Trim()
        }
    }
}
if (-not ($env:REG_USERNAME -and $env:REG_KEY -and $env:REG_APPCODE -and $env:REG_SECRET)) {
    Write-Host '[err] .env 凭证缺失，无法测试'; exit 1
}
Write-Host '[cfg] credentials loaded: YES (never printed)'

$base     = 'https://tzsb.scjgj.cq.gov.cn:1443'
$hostName = 'tzsb.scjgj.cq.gov.cn'
$mroot    = $base + '/api/wlw/maintenance'

function Invoke-Form {
    param([string]$Url, [string]$Body, [string]$Token, [string]$Method = 'POST')
    $cargs = @('-sS','-k','-m','25','-X', $Method,
        '-H', ('Host: ' + $hostName),
        '-H','Accept: application/json')
    if ($Token) { $cargs += @('-H', ('Authorization: Bearer ' + $Token)) }
    if ($Body) {
        $cargs += @('-H','Content-Type: application/x-www-form-urlencoded')
        $cargs += @('--data-raw', $Body)
    }
    $cargs += @('-w','__HTTP__%{http_code}', $Url)
    $raw  = (& curl.exe @cargs 2>&1) -join "`n"
    $http = '?'
    if ($raw -match '__HTTP__(\d+)\s*$') {
        $http = $Matches[1]
        $raw  = ($raw -replace '__HTTP__\d+\s*$','').Trim()
    }
    return [pscustomobject]@{ Http = $http; Body = $raw }
}

# ---- 1) 2.1 登录（GET + 查询串，docs/07 实测） ----
$q = 'username='  + [uri]::EscapeDataString($env:REG_USERNAME) +
     '&key='      + [uri]::EscapeDataString($env:REG_KEY) +
     '&appcode='  + [uri]::EscapeDataString($env:REG_APPCODE) +
     '&secret='   + [uri]::EscapeDataString($env:REG_SECRET)
$login = Invoke-Form -Url ($base + '/api/authService/login?' + $q) -Body $null -Method GET
$token = $null
if ($login.Body -match '"access_token":"([^"]+)"') { $token = $Matches[1] }
if ($token) { Write-Host '[2.1] login OK, token acquired' }
else        { Write-Host ('[2.1] login FAILED http=' + $login.Http + ' resp=' + ($login.Body -replace '\s+',' ').Substring(0, [Math]::Min(200, $login.Body.Length))); exit 1 }

# ---- 2) 2.2 主体查询：目标单位信用代码 ----
Write-Host ''
Write-Host ('[2.2] organizationCode = ' + $OrgCode)
$r22 = Invoke-Form -Url ($mroot + '/entity/queryID') -Body ('organizationCode=' + [uri]::EscapeDataString($OrgCode)) -Token $token
Write-Host ('  http=' + $r22.Http)
Write-Host ('  resp=' + $r22.Body)

# ---- 3) 2.5 人员列表（token 所属单位；workEndDate 须大于当前日期） ----
Write-Host ''
$end = (Get-Date).AddYears(1).ToString('yyyy-MM-dd')
Write-Host ('[2.5] queryWorkList changState=0, workEndDate=' + $end)
$r25 = Invoke-Form -Url ($mroot + '/entity/queryWorkList') -Body ('changState=0&workEndDate=' + $end) -Token $token
Write-Host ('  http=' + $r25.Http)
Write-Host ('  resp=' + $r25.Body)

# ---- 4) 简单解析统计 ----
try {
    $j = $r25.Body | ConvertFrom-Json
    $list = $null
    if ($j.data -is [array]) { $list = $j.data }
    elseif ($j -is [array])  { $list = $j }
    if ($list) {
        Write-Host ''
        Write-Host ('[2.5] 人员数量: ' + $list.Count)
        $list | ForEach-Object {
            Write-Host ('  - id=' + $_.id + '  name=' + $_.workManName + '  cert=' + $_.workManCertificate + '  stat=' + $_.workStat + '  合同:' + $_.workStartDate + '~' + $_.workEndDate)
        }
    } else {
        Write-Host '[2.5] 未解析到人员数组（三级回退均失败或确实为空）'
    }
} catch {
    Write-Host '[2.5] 响应非 JSON，见上方原始报文'
}
