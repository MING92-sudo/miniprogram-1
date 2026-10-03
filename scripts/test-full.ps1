# ============================================================
# test-full.ps1 - 全接口联调（用户已确认，对象电梯 212520 / 渝达物业）
# 链路: 2.2 主体确认 → 2.3 建立服务关系 → 2.4 登记人员 → 2.5 取 platform_id
# 注意: 2.3/2.4 为写操作，已在平台产生真实记录，后续可用 changState=1 终止
# ============================================================
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Get-Content (Join-Path $root '.env') | ForEach-Object {
    if ($_ -match '^\s*(REG_[A-Z_]+)\s*=\s*(.*)\s*$') {
        Set-Item -Path ("Env:" + $Matches[1]) -Value $Matches[2].Trim()
    }
}
$base     = 'https://tzsb.scjgj.cq.gov.cn:1443'
$mroot    = $base + '/api/wlw/maintenance'
$elevReg  = '31105001062011050006'
$yudaEntityId = '5748838430347886614'

function Invoke-Api {
    param([string]$Url, [string]$Body, [string]$Method = 'POST')
    $cargs = @('-sS','-k','-m','30','-X',$Method,
        '-H','Accept: application/json')
    if ($script:Token) { $cargs += @('-H', ('Authorization: Bearer ' + $script:Token)) }
    if ($Body) { $cargs += @('-H','Content-Type: application/x-www-form-urlencoded','--data-raw', $Body) }
    $cargs += @('-w','__HTTP__%{http_code}', $Url)
    $raw = (& curl.exe @cargs 2>&1) -join "`n"
    if ($raw -match '__HTTP__(\d+)\s*$') { return [pscustomobject]@{ Http = $Matches[1]; Body = (($raw -replace '__HTTP__\d+\s*$','').Trim()) } }
    return [pscustomobject]@{ Http = '?'; Body = $raw }
}

# 2.1 登录
$q = 'username='  + [uri]::EscapeDataString($env:REG_USERNAME) +
     '&key='      + [uri]::EscapeDataString($env:REG_KEY) +
     '&appcode='  + [uri]::EscapeDataString($env:REG_APPCODE) +
     '&secret='   + [uri]::EscapeDataString($env:REG_SECRET)
$login = Invoke-Api -Url ($base + '/api/authService/login?' + $q) -Method GET
if ($login.Body -match '"access_token":"([^"]+)"') { $script:Token = $Matches[1]; Write-Host '[2.1] login OK' }
else { Write-Host ('[2.1] FAILED: ' + $login.Body); exit 1 }

# 2.2 主体查询：渝达（试 unitName 单参，验证通用性）
Write-Host ''
Write-Host '[2.2] unitName=重庆市沙坪坝区渝达物业管理有限公司（无 organizationCode）'
$r22 = Invoke-Api -Url ($mroot + '/entity/queryID') -Body ('unitName=' + [uri]::EscapeDataString('重庆市沙坪坝区渝达物业管理有限公司'))
Write-Host ('  resp=' + $r22.Body)

# 2.3 建立与渝达的维保服务关系（multipart + 合同文件）
Write-Host ''
Write-Host '[2.3] updateServiceState 渝达 2026-09-30 ~ 2027-12-31 (multipart + contractFile)'
$r23 = (& curl.exe -sS -k -m 30 -X POST `
    -H ('Authorization: Bearer ' + $script:Token) `
    -F 'useUnitName=重庆市沙坪坝区渝达物业管理有限公司' `
    -F ('useUnitEntityID=' + $yudaEntityId) `
    -F 'changState=0' `
    -F 'serviceStartDate=2026-09-30' `
    -F 'serviceEndDate=2027-12-31' `
    -F ('contractFile=@' + (Join-Path $PSScriptRoot 'fixtures\contract-test.pdf') + ';type=application/pdf') `
    -w '__HTTP__%{http_code}' ($mroot + '/entity/updateServiceState') 2>&1) -join "`n"
Write-Host ('  resp=' + ($r23 -replace '\s+',' '))

# 2.5 关系建立后查人员列表
Write-Host ''
$end = (Get-Date).AddYears(1).ToString('yyyy-MM-dd')
$r25a = Invoke-Api -Url ($mroot + '/entity/queryWorkList') -Body ('changState=0&workEndDate=' + $end)
Write-Host ('[2.5] 建关系后: http=' + $r25a.Http + '  resp=' + $r25a.Body)

# 2.4 登记维保人员 张伟（multipart + 证书文件）
Write-Host ''
Write-Host '[2.4] updateWorkState 张伟 cert=CQ3601030001 (multipart + certificateFile)'
$r24 = (& curl.exe -sS -k -m 30 -X POST `
    -H ('Authorization: Bearer ' + $script:Token) `
    -F 'workManName=张伟' `
    -F 'workManCertificate=CQ3601030001' `
    -F 'workStartDate=2024-03-01' `
    -F 'workEndDate=2027-11-04' `
    -F 'changState=0' `
    -F 'workManPhone=13800000001' `
    -F ('certificateFile=@' + (Join-Path $PSScriptRoot 'fixtures\cert-test.pdf') + ';type=application/pdf') `
    -w '__HTTP__%{http_code}' ($mroot + '/entity/updateWorkState') 2>&1) -join "`n"
Write-Host ('  resp=' + ($r24 -replace '\s+',' '))

# 2.5 再查：按证书号精确匹配 platform_id
Write-Host ''
$r25b = Invoke-Api -Url ($mroot + '/entity/queryWorkList') -Body ('changState=0&workEndDate=' + $end)
Write-Host ('[2.5] 注册人员后: http=' + $r25b.Http)
Write-Host ('  resp=' + $r25b.Body)
try {
    $j = $r25b.Body | ConvertFrom-Json
    $list = $null
    if ($j.data -is [array]) { $list = $j.data } elseif ($j -is [array]) { $list = $j }
    if ($list) {
        Write-Host ('  人员数量: ' + $list.Count)
        $list | ForEach-Object {
            Write-Host ('  - id=' + $_.id + '  name=' + $_.workManName + '  cert=' + $_.workManCertificate + '  stat=' + $_.workStat)
        }
    }
} catch { }
