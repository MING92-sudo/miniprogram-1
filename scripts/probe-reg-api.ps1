# ============================================================
# probe-reg-api.ps1 - Regulatory platform (wlw) API one-shot probe
#
# Purpose : run a full integration test and report WHICH endpoints work.
# Usage   : powershell -ExecutionPolicy Bypass -File .\scripts\probe-reg-api.ps1
# Creds   : auto-loaded from .env at project root
#           (REG_USERNAME / REG_KEY / REG_APPCODE / REG_SECRET).
#           Missing .env -> only unauthenticated probes are run.
# Reference: docs/07 field-test log (response-format cheat sheet, section 4).
#
# SAFETY  : write endpoints (2.3/2.4/2.6/2.8) are probed with an EMPTY body {}
#           -> a param-validation reply proves the route exists WITHOUT
#              creating any record on the platform. Never put real data here.
# ============================================================

$ErrorActionPreference = 'Continue'
$root    = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'

# ---- load .env (values are never printed) --------------------------------
$hasCreds = $false
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        if ($_ -match '^\s*(REG_[A-Z_]+)\s*=\s*(.*)\s*$') {
            Set-Item -Path ("Env:" + $Matches[1]) -Value $Matches[2].Trim()
        }
    }
    $hasCreds = [bool]($env:REG_USERNAME -and $env:REG_KEY -and $env:REG_APPCODE -and $env:REG_SECRET)
}
if ($hasCreds) { Write-Host '[cfg] credentials loaded from .env: YES' }
else          { Write-Host '[cfg] credentials loaded from .env: NO  (unauthenticated probes only)' }

# ---- probe helper ---------------------------------------------------------
$script:Results = New-Object System.Collections.Generic.List[object]

function Invoke-Probe {
    param(
        [string]$Id,
        [string]$Url,
        [string]$HostName,
        [string]$Method = 'GET',
        [string]$Body,
        [string]$Token,
        [string]$DisplayUrl
    )
    if (-not $DisplayUrl) { $DisplayUrl = $Url }

    $cargs = @('-sS','-k','-m','20','-X',$Method, '-H', ('Host: ' + $HostName))
    if ($Token) { $cargs += @('-H', ('Authorization: Bearer ' + $Token)) }
    # 2026-09-29: business endpoints actually accept form-urlencoded, NOT JSON (docs/07 ch.12)
    if ($Body)  { $cargs += @('-H','Content-Type: application/x-www-form-urlencoded','-H','Accept: application/json','--data-raw',$Body) }
    $cargs += @('-w','__HTTPCODE__%{http_code}', $Url)

    $raw  = (& curl.exe @cargs 2>&1) -join "`n"
    $http = '?'
    if ($raw -match '__HTTPCODE__(\d+)\s*$') {
        $http = $Matches[1]
        $raw  = ($raw -replace '__HTTPCODE__\d+\s*$','').Trim()
    }
    $excerpt = ($raw -replace '\s+',' ')
    if ($excerpt.Length -gt 180) { $excerpt = $excerpt.Substring(0,180) + '...' }

    # classify -- ASCII markers only (see docs/07 section 4)
    $verdict = 'UNKNOWN'
    if     ($raw -match '(?i)<html')                                          { $verdict = 'NGINX (bypassed gateway, missing /api prefix?)' }
    elseif ($raw -match '"timestamp"' -and $raw -match '"status":\s*404')      { $verdict = 'ROUTE-MISSING (Spring 404: route not registered yet)' }
    elseif ($http -eq '401' -or $raw -match '"code":\s*401')                   { $verdict = 'AUTH-FAIL (gateway rejected token)' }
    elseif ($raw -match '"status":\s*415')                                     { $verdict = 'ROUTE-ALIVE (415 media-type; route exists)' }
    elseif ($raw -match '"code"')                                              { $verdict = 'ROUTE-ALIVE (business response)' }
    elseif ($http -eq '000' -or $http -eq '?')                                 { $verdict = 'CONN-FAIL (network/DNS/timeout/port)' }

    $script:Results.Add([pscustomobject]@{
        Id = $Id; Method = $Method; Url = $DisplayUrl; Http = $http
        Verdict = $verdict; Body = $excerpt
    })

    Write-Host ("[{0}] {1} {2}" -f $Id, $Method, $DisplayUrl)
    Write-Host ("      HTTP {0}  ->  {1}" -f $http, $verdict)
    Write-Host ("      body: {0}" -f $excerpt)
    Write-Host ''
    return $raw
}

# ---- 1) pick a reachable base URL ----------------------------------------
# doc/07: DNS may be broken; IP 183.66.103.15 + Host header worked (DEV ONLY)
function Test-Base {
    param([string]$Url, [string]$HostName)
    $cargs = @('-sS','-k','-m','12','-H', ('Host: ' + $HostName),
               '-w','__HC__%{http_code}', ($Url + '/api/wlw/actuator/health'))
    $raw = (& curl.exe @cargs 2>&1) -join ''
    # 2026-09-29: gateway now requires auth for actuator too -> 401 counts as
    # "service reachable"; anonymous {"status":"UP"} kept for compatibility.
    return ($raw -match '"status"\s*:\s*"UP"' -or $raw -match '__HC__401\s*$')
}

$candidates = @(
    @{ H = 'tzsb.scjgj.cq.gov.cn'; Via = 'DNS' },                # .env.example assigned address
    @{ H = 'tszb.scjgj.cq.gov.cn'; Via = 'DNS' },                # spelling used in docs/07
    @{ H = 'tzsb.scjgj.cq.gov.cn'; Via = 'IP:183.66.103.15' }    # dev-only fallback
)

$base = $null; $hostName = $null
foreach ($c in $candidates) {
    if ($c.Via -eq 'DNS') {
        try { [void]([System.Net.Dns]::GetHostAddresses($c.H)) }
        catch { Write-Host ("[dns] unresolved: {0}" -f $c.H); continue }
        $url = 'https://' + $c.H + ':1443'
    } else {
        $ip  = $c.Via -replace '^IP:',''
        $url = 'https://' + $ip + ':1443'
    }
    Write-Host ("[probe] trying {0}  (Host: {1}, via {2})" -f $url, $c.H, $c.Via)
    if (Test-Base -Url $url -HostName $c.H) {
        $base = $url; $hostName = $c.H
        Write-Host '[probe] healthy -> using this base URL'
        break
    }
    Write-Host '[probe] not healthy, trying next candidate'
}
if (-not $base) {
    Write-Host 'FATAL: no reachable base URL. Check network / VPN / firewall.'
    exit 1
}
Write-Host ("[base] {0}  (Host: {1})" -f $base, $hostName)
Write-Host ''

# ---- 2) unauthenticated probes -------------------------------------------
Invoke-Probe -Id 'GW-HEALTH'    -Url ($base + '/api/actuator/health')    -HostName $hostName
Invoke-Probe -Id 'WLW-HEALTH'   -Url ($base + '/api/wlw/actuator/health') -HostName $hostName
# bare login (no params): business 10001 ":username required" proves authService is alive
Invoke-Probe -Id 'LOGIN-NOAUTH' -Url ($base + '/api/authService/login')   -HostName $hostName `
    -DisplayUrl ($base + '/api/authService/login')

# ---- 3) login (2.1, GET + query string -- NOT POST) ----------------------
$token = $null
if ($hasCreds) {
    # NOTE: '&' must stay inside a quoted string (pitfall #2 in docs/07)
    $q = 'username='  + [uri]::EscapeDataString($env:REG_USERNAME) +
         '&key='      + [uri]::EscapeDataString($env:REG_KEY) +
         '&appcode='  + [uri]::EscapeDataString($env:REG_APPCODE) +
         '&secret='   + [uri]::EscapeDataString($env:REG_SECRET)
    $r = Invoke-Probe -Id 'LOGIN(2.1)' -Url ($base + '/api/authService/login?' + $q) -HostName $hostName `
        -DisplayUrl ($base + '/api/authService/login?username=***&key=***&appcode=***&secret=***')
    if ($r -match '"access_token":"([^"]+)"') {
        $token = $Matches[1]
        Write-Host '[auth] token acquired'
        # decode JWT payload claims (orgid / exp) -- useful, spec never documented them
        $parts = $token.Split('.')
        if ($parts.Count -ge 2) {
            $p = $parts[1].Replace('-','+').Replace('_','/')
            switch ($p.Length % 4) { 2 { $p += '==' } 3 { $p += '=' } }
            try {
                $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p))
                if ($claims -match '"orgid":"([^"]+)"') { Write-Host ("[auth] orgid = " + $Matches[1]) }
                if ($claims -match '"exp":(\d+)') {
                    $exp = [DateTimeOffset]::FromUnixTimeSeconds([long]$Matches[1]).LocalDateTime
                    Write-Host ("[auth] token expires (local): " + $exp)
                }
            } catch { }
        }
    } else {
        Write-Host '[auth] NO token in response -- check credentials / GET method'
    }
    Write-Host ''
}

# ---- 4) business endpoints 2.2-2.8 (empty body = safe liveness probe) ----
if ($token) {
    $mroot = $base + '/api/wlw/maintenance'
    Invoke-Probe -Id '2.2 QUERY-ID'        -Method POST -Url ($mroot + '/entity/queryID')              -Body ('organizationCode=' + [uri]::EscapeDataString($env:REG_USERNAME)) -Token $token -HostName $hostName
    Invoke-Probe -Id '2.3 SERVICE-STATE'   -Method POST -Url ($mroot + '/entity/updateServiceState')   -Body '{}' -Token $token -HostName $hostName
    Invoke-Probe -Id '2.4 WORK-STATE'      -Method POST -Url ($mroot + '/entity/updateWorkState')      -Body '{}' -Token $token -HostName $hostName
    Invoke-Probe -Id '2.5 QUERY-WORKLIST'  -Method POST -Url ($mroot + '/entity/queryWorkList')        -Body 'changState=0' -Token $token -HostName $hostName
    Invoke-Probe -Id '2.6 RECORD-UPLOAD'   -Method POST -Url ($mroot + '/elevator/maintenanceRecord')  -Body '{}' -Token $token -HostName $hostName
    Invoke-Probe -Id '2.7 ELEV-INFO'       -Method POST -Url ($mroot + '/equipment/queryElevatorInfo') -Body 'deviceCode=TEST' -Token $token -HostName $hostName
    Invoke-Probe -Id '2.8 LEGACY-UPLOAD'   -Method POST -Url ($mroot + '/record/uploadMaintainRecord') -Body '{}' -Token $token -HostName $hostName
} else {
    Write-Host '[skip] business endpoints 2.2-2.8 need a token -> fill .env and re-run'
    Write-Host ''
}

# ---- 5) summary ------------------------------------------------------------
Write-Host '================ SUMMARY ================'
$rows = $script:Results | ForEach-Object {
    $usable = switch -Wildcard ($_.Verdict) {
        'ROUTE-ALIVE*' { 'YES' }
        'CONN-FAIL*'   { 'N/A' }
        default        { 'NO'  }
    }
    [pscustomobject]@{ Id = $_.Id; Method = $_.Method; Http = $_.Http; Usable = $usable; Verdict = $_.Verdict }
}
$rows | Format-Table -AutoSize | Out-String | Write-Host

Write-Host '--- Verdict legend ---'
Write-Host '  ROUTE-ALIVE   : endpoint exists and answered -> USABLE (params may still need tuning)'
Write-Host '  ROUTE-MISSING : Spring 404 -> platform has NOT deployed the route yet (ticket #13)'
Write-Host '  AUTH-FAIL     : token missing / invalid / expired'
Write-Host '  CONN-FAIL     : network / DNS / port problem'
Write-Host '  NGINX         : request bypassed the gateway (path must start with /api)'
Write-Host ''
Write-Host 'Reminder: REAL-data tests for 2.6 idempotency must use a dedicated test elevator'
Write-Host '          (docs/04 B.6). This probe never sends real data.'
