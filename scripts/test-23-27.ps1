# ============================================================
# test-23-27.ps1 - 2.3 建立维保服务关系（写操作，已获用户确认）→ 2.5 → 2.7
#
# 对象  : 重庆星禹佳物业管理有限公司 entityID=311753437688189
# 服务期: 2026-09-30（签约日）~ 2027-12-31
# 说明  : 2.3 无合同文件时先试 form-urlencoded，415 再试 multipart；
#         2.7 规范仅支持 factoryNumber/registrationCode/deviceCode 三条件，
#         脚本同时试探 useUnitEntityId 参数与空参（验证建关系后能否按单位枚举）。
# ============================================================
param(
    [string]$UseUnitEntityId = '311753437688189',
    [string]$StartDate = '2026-09-30',
    [string]$EndDate = '2027-12-31'
)

$ErrorActionPreference = 'Continue'
$root    = Split-Path -Parent $PSScriptRoot
Get-Content (Join-Path $root '.env') | ForEach-Object {
    if ($_ -match '^\s*(REG_[A-Z_]+)\s*=\s*(.*)\s*$') {
        Set-Item -Path ("Env:" + $Matches[1]) -Value $Matches[2].Trim()
    }
}
$base     = 'https://tzsb.scjgj.cq.gov.cn:1443'
$hostName = 'tzsb.scjgj.cq.gov.cn'
$mroot    = $base + '/api/wlw/maintenance'

function Invoke-Api {
    param([string]$Url, [string]$Body, [string]$Method = 'POST', [switch]$Multipart)
    $cargs = @('-sS','-k','-m','25','-X',$Method,
        '-H', ('Host: ' + $hostName),
        '-H','Accept: application/json')
    if ($script:Token) { $cargs += @('-H', ('Authorization: Bearer ' + $script:Token)) }
    if ($Body -and -not $Multipart) {
        $cargs += @('-H','Content-Type: application/x-www-form-urlencoded','--data-raw', $Body)
    }
    if ($Multipart) { $cargs += @('-X','POST') }
    $cargs += @('-w','__HTTP__%{http_code}', $Url)
    $raw  = (& curl.exe @cargs 2>&1) -join "`n"
    $http = '?'
    if ($raw -match '__HTTP__(\d+)\s*$') {
        $http = $Matches[1]
        $raw  = ($raw -replace '__HTTP__\d+\s*$','').Trim()
    }
    return [pscustomobject]@{ Http = $http; Body = $raw }
}

# ---- 2.1 登录 ----
$q = 'username='  + [uri]::EscapeDataString($env:REG_USERNAME) +
     '&key='      + [uri]::EscapeDataString($env:REG_KEY) +
     '&appcode='  + [uri]::EscapeDataString($env:REG_APPCODE) +
     '&secret='   + [uri]::EscapeDataString($env:REG_SECRET)
$login = Invoke-Api -Url ($base + '/api/authService/login?' + $q) -Method GET
if ($login.Body -match '"access_token":"([^"]+)"') { $script:Token = $Matches[1]; Write-Host '[2.1] login OK' }
else { Write-Host ('[2.1] FAILED: ' + $login.Body); exit 1 }

# ---- 2.3 建立服务关系（写操作）----
Write-Host ''
Write-Host ('[2.3] useUnitEntityID=' + $UseUnitEntityId + '  服务期 ' + $StartDate + ' ~ ' + $EndDate)
$body23 = 'useUnitEntityID=' + $UseUnitEntityId +
    '&changState=0' +
    '&serviceStartDate=' + $StartDate +
    '&serviceEndDate=' + $EndDate
$r23 = Invoke-Api -Url ($mroot + '/entity/updateServiceState') -Body $body23
Write-Host ('  form : http=' + $r23.Http + '  resp=' + $r23.Body)
if ($r23.Http -eq '415' -or $r23.Body -match 'Content-Type') {
    Write-Host '  重试 multipart/form-data ...'
    $r23 = Invoke-Api -Url ($mroot + '/entity/updateServiceState') -Multipart
    # curl -F 形式
    $fargs = @('-sS','-k','-m','25','-X','POST',
        '-H', ('Host: ' + $hostName),
        '-H', ('Authorization: Bearer ' + $script:Token),
        '-F', ('useUnitEntityID=' + $UseUnitEntityId),
        '-F', 'changState=0',
        '-F', ('serviceStartDate=' + $StartDate),
        '-F', ('serviceEndDate=' + $EndDate),
        '-w','__HTTP__%{http_code}', ($mroot + '/entity/updateServiceState'))
    $raw = (& curl.exe @fargs 2>&1) -join "`n"
    Write-Host ('  multipart: ' + ($raw -replace '\s+',' '))
}

# ---- 2.5 复查人员列表（关系建立后应放行）----
Write-Host ''
$end = (Get-Date).AddYears(1).ToString('yyyy-MM-dd')
$r25 = Invoke-Api -Url ($mroot + '/entity/queryWorkList') -Body ('changState=0&workEndDate=' + $end)
Write-Host ('[2.5] http=' + $r25.Http + '  resp=' + $r25.Body)

# ---- 2.7 电梯枚举试探 ----
Write-Host ''
Write-Host '[2.7] 试探按使用单位枚举电梯'
$r27a = Invoke-Api -Url ($mroot + '/equipment/queryElevatorInfo') -Body ''
Write-Host ('  空参       : http=' + $r27a.Http + '  resp=' + $r27a.Body)
$r27b = Invoke-Api -Url ($mroot + '/equipment/queryElevatorInfo') -Body ('useUnitEntityId=' + $UseUnitEntityId)
Write-Host ('  按单位ID   : http=' + $r27b.Http + '  resp=' + $r27b.Body)
$r27c = Invoke-Api -Url ($mroot + '/equipment/queryElevatorInfo') -Body ('registrationCode=1')
Write-Host ('  任意登记证号: http=' + $r27c.Http + '  resp=' + $r27c.Body)
