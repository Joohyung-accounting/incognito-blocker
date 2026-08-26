#Requires -Version 5.1
<#
.SYNOPSIS
    IncognitoBlocker (Windows) - 데스크톱 브라우저의 시크릿/프라이빗 모드를 정책으로 차단합니다.

.DESCRIPTION
    HKLM\SOFTWARE\Policies 아래에 각 브라우저의 엔터프라이즈 정책을 기록합니다.
    HKLM 은 관리자만 쓸 수 있으므로, 차단 대상이 '표준 사용자' 계정을 쓰는 한
    본인 힘으로는 되돌릴 수 없는 하드 차단이 됩니다.

    이 스크립트는 세 가지를 함께 처리합니다.
      1) 시크릿/프라이빗 모드 비활성화
      2) 게스트 프로필 / 새 프로필 추가 차단
         (게스트 프로필은 Chromium 공식 문서상 "모든 창이 시크릿 모드인 프로필" 입니다.
          이걸 안 막으면 시크릿 차단은 사실상 무의미합니다.)
      3) 차단을 무효화하는 allowlist 정책 제거
         (IncognitoModeUrlAllowlist / InPrivateModeUrlAllowlist 는 차단보다 우선합니다)

.PARAMETER Action
    Apply  : 차단 정책 적용
    Remove : 차단 정책 해제
    Status : 현재 상태만 출력 (기본값, 관리자 권한 불필요)

.PARAMETER SkipGuestProfileBlock
    게스트/새 프로필 차단을 건너뜁니다. 권장하지 않습니다.

.PARAMETER CloseBrowsers
    적용 후 실행 중인 브라우저를 강제 종료합니다.
    Chrome 의 IncognitoModeAvailability 는 dynamic refresh 를 지원하지 않아
    브라우저를 재시작해야만 적용되며, 이미 열린 시크릿 창은 닫히지 않습니다.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Set-IncognitoBlock.ps1 -Action Status

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Set-IncognitoBlock.ps1 -Action Apply -CloseBrowsers
#>
[CmdletBinding()]
param(
    [ValidateSet('Apply', 'Remove', 'Status')]
    [string]$Action = 'Status',

    [switch]$SkipGuestProfileBlock,

    [switch]$CloseBrowsers
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 정책 값 참고
#
#   Chromium 계열 IncognitoModeAvailability (REG_DWORD)
#      0 = 시크릿 모드 사용 가능(기본)
#      1 = 시크릿 모드 사용 안 함   <-- 우리가 쓰는 값
#      2 = 항상 시크릿 모드         <-- 절대 쓰면 안 됨(정반대 효과)
#
#   Edge InPrivateModeAvailability (REG_DWORD)  0 가능 / 1 차단 / 2 강제
#
#   Firefox PrivateBrowsingModeAvailability (REG_DWORD)  0 허용 / 1 차단 / 2 강제
#      - Firefox 130+, ESR 128.2+ 에서 사용 가능한 최신 정책명
#      - 구버전 호환을 위해 레거시 DisablePrivateBrowsing = 1 도 함께 설정
# ---------------------------------------------------------------------------

$Browsers = @(
    [pscustomobject]@{
        Name        = 'Google Chrome'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Google\Chrome'
        Block       = [ordered]@{ IncognitoModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        # 이 값들이 남아 있으면 차단이 무효화된다. 적용 시 반드시 지운다.
        MustNotExist = @('IncognitoModeUrlAllowlist', 'BrowserGuestModeEnforced', 'ForceEphemeralProfiles')
        Exe         = 'chrome'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\chrome.exe'
        Verified    = $true
    }
    [pscustomobject]@{
        Name        = 'Microsoft Edge'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Microsoft\Edge'
        Block       = [ordered]@{ InPrivateModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddProfileEnabled = 0 }
        MustNotExist = @('InPrivateModeUrlAllowlist')
        Exe         = 'msedge'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe'
        Verified    = $true
    }
    [pscustomobject]@{
        Name        = 'Mozilla Firefox'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Mozilla\Firefox'
        # 신규 + 레거시 정책을 함께 건다. Firefox 버전을 모르기 때문이다.
        Block       = [ordered]@{ PrivateBrowsingModeAvailability = 1; DisablePrivateBrowsing = 1 }
        GuestBlock  = [ordered]@{ DisableProfileImport = 1; DisableProfileRefresh = 1 }
        MustNotExist = @()
        Exe         = 'firefox'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\firefox.exe'
        Verified    = $true
    }
    [pscustomobject]@{
        Name        = 'Brave'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\BraveSoftware\Brave'
        Block       = [ordered]@{ IncognitoModeAvailability = 1; TorDisabled = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        MustNotExist = @('IncognitoModeUrlAllowlist')
        Exe         = 'brave'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\brave.exe'
        Verified    = $true   # TorDisabled 철자만 Brave 문서로 재확인 권장
    }
    [pscustomobject]@{
        Name        = 'Chromium'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Chromium'
        Block       = [ordered]@{ IncognitoModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        MustNotExist = @('IncognitoModeUrlAllowlist')
        Exe         = 'chromium'
        AppPath     = $null
        Verified    = $true
    }
    # ↓ 아래 두 개는 벤더가 정책 경로를 공개하지 않았습니다.
    #   값을 써 넣어도 무해하지만, 실제로 먹히는지는 vivaldi://policy / whale://policy 에서
    #   직접 확인해야 합니다. 확인 전에는 '적용됨'으로 신뢰하지 마세요.
    [pscustomobject]@{
        Name        = 'Vivaldi (미검증 경로)'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Vivaldi'
        Block       = [ordered]@{ IncognitoModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        MustNotExist = @()
        Exe         = 'vivaldi'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\vivaldi.exe'
        Verified    = $false
    }
    [pscustomobject]@{
        Name        = '네이버 웨일 (미검증 경로)'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\Naver\Naver Whale'
        Block       = [ordered]@{ IncognitoModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        MustNotExist = @()
        Exe         = 'whale'
        AppPath     = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\whale.exe'
        Verified    = $false
    }
    [pscustomobject]@{
        Name        = '네이버 웨일 (대체 경로)'
        PolicyKey   = 'HKLM:\SOFTWARE\Policies\NaverWhale\Whale'
        Block       = [ordered]@{ IncognitoModeAvailability = 1 }
        GuestBlock  = [ordered]@{ BrowserGuestModeEnabled = 0; BrowserAddPersonEnabled = 0 }
        MustNotExist = @()
        Exe         = $null
        AppPath     = $null
        Verified    = $false
    }
)

# 정책 자체가 존재하지 않는 브라우저 - 실행 차단(AppLocker) 외에는 방법이 없다
$NoPolicySupport = @(
    @{ Name = 'Opera';    Exe = 'opera'    },
    @{ Name = 'Opera GX'; Exe = 'operagx'  },
    @{ Name = 'Tor Browser'; Exe = 'tor'   }
)

# ---------------------------------------------------------------------------
# 헬퍼
# ---------------------------------------------------------------------------

function Test-Administrator {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-BrowserInstalled {
    param([string]$AppPath)
    if ([string]::IsNullOrEmpty($AppPath)) { return $false }
    return (Test-Path -LiteralPath $AppPath)
}

function Set-PolicyValue {
    param([string]$Key, [string]$Name, [int]$Value)
    if (-not (Test-Path -LiteralPath $Key)) { New-Item -Path $Key -Force | Out-Null }
    New-ItemProperty -LiteralPath $Key -Name $Name -Value $Value -PropertyType DWord -Force | Out-Null
}

function Remove-PolicyValue {
    param([string]$Key, [string]$Name)
    if (-not (Test-Path -LiteralPath $Key)) { return }
    $p = Get-ItemProperty -LiteralPath $Key -ErrorAction SilentlyContinue
    if ($null -ne $p -and $p.PSObject.Properties.Name -contains $Name) {
        Remove-ItemProperty -LiteralPath $Key -Name $Name -Force -ErrorAction SilentlyContinue
    }
}

function Remove-PolicySubKey {
    param([string]$Key, [string]$SubKey)
    $full = Join-Path $Key $SubKey
    if (Test-Path -LiteralPath $full) {
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-PolicyValue {
    param([string]$Key, [string]$Name)
    if (-not (Test-Path -LiteralPath $Key)) { return $null }
    $p = Get-ItemProperty -LiteralPath $Key -ErrorAction SilentlyContinue
    if ($null -eq $p) { return $null }
    if ($p.PSObject.Properties.Name -notcontains $Name) { return $null }
    return $p.$Name
}

function Get-EffectiveSettings {
    param([pscustomobject]$Browser)
    $settings = [ordered]@{}
    foreach ($k in $Browser.Block.Keys) { $settings[$k] = $Browser.Block[$k] }
    if (-not $SkipGuestProfileBlock) {
        foreach ($k in $Browser.GuestBlock.Keys) { $settings[$k] = $Browser.GuestBlock[$k] }
    }
    return $settings
}

# ---------------------------------------------------------------------------
# 실행
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host '  IncognitoBlocker - Windows 데스크톱 정책 엔진' -ForegroundColor Cyan
Write-Host '  =============================================' -ForegroundColor DarkGray

$isAdmin = Test-Administrator
if ($Action -ne 'Status' -and -not $isAdmin) {
    Write-Host ''
    Write-Host '  [X] 관리자 권한이 필요합니다.' -ForegroundColor Red
    Write-Host '      PowerShell 을 "관리자 권한으로 실행" 한 뒤 다시 시도하세요.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

$rows = @()

foreach ($b in $Browsers) {
    $settings = Get-EffectiveSettings -Browser $b
    $installed = Test-BrowserInstalled -AppPath $b.AppPath

    if ($Action -eq 'Apply') {
        foreach ($name in @($settings.Keys)) {
            Set-PolicyValue -Key $b.PolicyKey -Name $name -Value $settings[$name]
        }
        # 차단을 무효화하는 값들을 제거한다
        foreach ($bad in $b.MustNotExist) {
            Remove-PolicyValue -Key $b.PolicyKey -Name $bad
            Remove-PolicySubKey -Key $b.PolicyKey -SubKey $bad
        }
        # 사용자가 되돌릴 수 있는 Recommended 하위 키도 정리
        Remove-PolicySubKey -Key $b.PolicyKey -SubKey 'Recommended'
    }
    elseif ($Action -eq 'Remove') {
        foreach ($name in @($settings.Keys)) {
            Remove-PolicyValue -Key $b.PolicyKey -Name $name
        }
    }

    $primaryName = @($b.Block.Keys)[0]
    $current = Get-PolicyValue -Key $b.PolicyKey -Name $primaryName
    $expected = $b.Block[$primaryName]

    if ($null -eq $current)          { $state = '미설정' }
    elseif ($current -eq $expected)  { $state = if ($b.Verified) { '차단됨' } else { '기록됨(미검증)' } }
    else                             { $state = "다른값($current)" }

    $rows += [pscustomobject]@{
        'Browser'   = $b.Name
        'Installed' = $(if ($installed) { 'O' } else { '-' })
        'Policy'    = $primaryName
        'State'     = $state
    }
}

Write-Host ''
$rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host

# 브라우저 재시작 처리
if ($Action -eq 'Apply' -and $CloseBrowsers) {
    $killed = @()
    foreach ($b in $Browsers) {
        if ([string]::IsNullOrEmpty($b.Exe)) { continue }
        $procs = Get-Process -Name $b.Exe -ErrorAction SilentlyContinue
        if ($procs) {
            $procs | Stop-Process -Force -ErrorAction SilentlyContinue
            $killed += $b.Name
        }
    }
    if ($killed.Count -gt 0) {
        Write-Host ("  실행 중이던 브라우저를 종료했습니다: " + ($killed -join ', ')) -ForegroundColor DarkGray
    }
}

# 안내
if ($Action -eq 'Apply') {
    Write-Host '  [OK] 차단 정책을 적용했습니다.' -ForegroundColor Green
    Write-Host ''
    Write-Host '  확인 방법: chrome://policy  edge://policy  about:policies' -ForegroundColor DarkGray
    Write-Host '            해당 정책이 목록에 보이면 실제로 적용된 것입니다.' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  반드시 확인해야 할 것 3가지' -ForegroundColor Yellow
    Write-Host '   1. 브라우저를 완전히 종료했다가 다시 켜야 적용됩니다.' -ForegroundColor Yellow
    Write-Host '      (Chrome 은 이미 열려 있는 시크릿 창을 닫아 주지 않습니다)' -ForegroundColor DarkGray
    Write-Host '   2. 차단 대상 사용자는 Windows "표준 사용자" 여야 합니다.' -ForegroundColor Yellow
    Write-Host '      관리자 계정이면 이 레지스트리 값을 직접 지울 수 있습니다.' -ForegroundColor DarkGray
    Write-Host '   3. Opera / Opera GX / Tor Browser 는 정책 자체가 없습니다.' -ForegroundColor Yellow
    Write-Host '      실행 자체를 막으려면 AppLocker 를 쓰거나 삭제해야 합니다.' -ForegroundColor DarkGray

    $found = @()
    foreach ($n in $NoPolicySupport) {
        if (Get-Process -Name $n.Exe -ErrorAction SilentlyContinue) { $found += $n.Name }
    }
    if ($found.Count -gt 0) {
        Write-Host ''
        Write-Host ("  [!] 지금 실행 중인 차단 불가 브라우저: " + ($found -join ', ')) -ForegroundColor Red
    }
}
elseif ($Action -eq 'Remove') {
    Write-Host '  [OK] 차단 정책을 해제했습니다. 브라우저를 재시작하면 원래대로 돌아갑니다.' -ForegroundColor Green
}
else {
    if (-not $isAdmin) {
        Write-Host '  (읽기 전용 모드 - 변경하려면 관리자 권한으로 실행)' -ForegroundColor DarkGray
    }
}
Write-Host ''
