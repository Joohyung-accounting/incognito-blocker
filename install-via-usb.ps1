#Requires -Version 5.1
<#
.SYNOPSIS
    USB 로 연결된 갤럭시에 IncognitoBlocker 를 설치합니다.

.DESCRIPTION
    이 방법은 Play 프로텍트, '알 수 없는 출처' 설정, 파일 전송 문제를 전부 우회합니다.
    설치가 계속 실패할 때 가장 확실한 경로입니다.

    준비 (폰에서 한 번만):
      1. 설정 ▸ 휴대전화 정보 ▸ 소프트웨어 정보 ▸ 빌드번호 를 7번 연속 탭
      2. 설정 ▸ 개발자 옵션 ▸ USB 디버깅 켜기
      3. 설정 ▸ 보안 및 개인정보 보호 ▸ 자동 차단 ▸ 끄기   (켜져 있으면 USB 설치도 막힙니다)
      4. PC 에 USB 연결 → 폰에 뜨는 "USB 디버깅을 허용하시겠습니까?" → 항상 허용 체크 후 확인

    그 다음 이 스크립트를 실행하면 됩니다.

.PARAMETER ApkPath
    설치할 APK. 생략하면 스크립트 옆의 IncognitoBlocker-*.apk 중 최신 것을 씁니다.

.PARAMETER EnableAccessibility
    설치 후 접근성 서비스까지 adb 로 켭니다.
    (설정 화면을 직접 만질 필요가 없어지고, Android 13+ 의 '제한된 설정' 잠금도 우회됩니다)
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [switch]$EnableAccessibility
)

$ErrorActionPreference = 'Stop'

$PKG = 'com.incognitoblocker'
$SVC = "$PKG/$PKG.service.GuardAccessibilityService"

# --- adb 찾기 ---------------------------------------------------------------
$adbCandidates = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ProgramFiles\Android\platform-tools\adb.exe"
)
$adb = $adbCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Host '  [X] adb 를 찾을 수 없습니다. Android platform-tools 를 설치하세요.' -ForegroundColor Red
    exit 1
}

# --- APK 찾기 ---------------------------------------------------------------
if (-not $ApkPath) {
    $ApkPath = Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'IncognitoBlocker-*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ApkPath -or -not (Test-Path -LiteralPath $ApkPath)) {
    Write-Host '  [X] APK 파일을 찾을 수 없습니다.' -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host '  IncognitoBlocker - USB 설치' -ForegroundColor Cyan
Write-Host '  ---------------------------' -ForegroundColor DarkGray
Write-Host "  APK: $(Split-Path $ApkPath -Leaf)  ($([math]::Round((Get-Item $ApkPath).Length/1KB)) KB)"
Write-Host ''

# --- 기기 확인 --------------------------------------------------------------
Write-Host '  기기를 찾는 중...' -ForegroundColor DarkGray
& $adb start-server 2>&1 | Out-Null
$devicesRaw = & $adb devices
$lines = $devicesRaw | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }

if ($lines.Count -eq 0) {
    Write-Host '  [X] 연결된 기기가 없습니다.' -ForegroundColor Red
    Write-Host ''
    Write-Host '     확인하세요:' -ForegroundColor Yellow
    Write-Host '      - USB 케이블이 연결되어 있는지 (충전 전용 케이블이면 안 됩니다)'
    Write-Host '      - 개발자 옵션 ▸ USB 디버깅 이 켜져 있는지'
    Write-Host '      - 폰 화면의 "USB 디버깅을 허용하시겠습니까?" 를 허용했는지'
    Write-Host '      - 설정 ▸ 보안 및 개인정보 보호 ▸ 자동 차단 이 꺼져 있는지'
    exit 1
}

$unauthorized = $lines | Where-Object { $_ -match 'unauthorized' }
if ($unauthorized) {
    Write-Host '  [X] 기기가 인증되지 않았습니다.' -ForegroundColor Red
    Write-Host '      폰 화면에 뜬 "USB 디버깅을 허용하시겠습니까?" 에서' -ForegroundColor Yellow
    Write-Host '      "이 컴퓨터에서 항상 허용" 체크 후 [허용] 을 누르고 다시 실행하세요.' -ForegroundColor Yellow
    exit 1
}

$model = (& $adb shell getprop ro.product.model) -join '' -replace "`r|`n", ''
$rel   = (& $adb shell getprop ro.build.version.release) -join '' -replace "`r|`n", ''
$sdk   = (& $adb shell getprop ro.build.version.sdk) -join '' -replace "`r|`n", ''
Write-Host "  기기: $model  (Android $rel / API $sdk)" -ForegroundColor Green
Write-Host ''

# --- 기존 버전 확인 ---------------------------------------------------------
$installed = (& $adb shell pm list packages $PKG) -join ''
if ($installed -match $PKG) {
    Write-Host '  기존 버전이 설치되어 있습니다. 제거합니다...' -ForegroundColor DarkGray
    Write-Host '  (서명 키가 바뀌면 덮어쓰기가 불가능합니다)' -ForegroundColor DarkGray
    & $adb uninstall $PKG 2>&1 | Out-Null
}

# --- 설치 ------------------------------------------------------------------
Write-Host '  설치 중...' -ForegroundColor DarkGray
$result = (& $adb install -r "$ApkPath" 2>&1) -join "`n"

if ($result -match 'Success') {
    Write-Host '  [OK] 설치 성공!' -ForegroundColor Green
} else {
    Write-Host '  [X] 설치 실패' -ForegroundColor Red
    Write-Host ''
    Write-Host $result -ForegroundColor DarkGray
    Write-Host ''
    # 실패 원인별 안내
    if ($result -match 'INSTALL_FAILED_USER_RESTRICTED') {
        Write-Host '  → 폰이 USB 설치를 거부했습니다.' -ForegroundColor Yellow
        Write-Host '    설정 ▸ 보안 및 개인정보 보호 ▸ 자동 차단  을 끄세요.' -ForegroundColor Yellow
        Write-Host '    개발자 옵션 ▸ "USB를 통해 설치된 앱 확인" 도 꺼야 할 수 있습니다.' -ForegroundColor Yellow
    } elseif ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|SIGNATURE') {
        Write-Host '  → 기존 앱과 서명이 다릅니다. 폰에서 IncognitoBlocker 를 직접 삭제한 뒤 다시 실행하세요.' -ForegroundColor Yellow
    } elseif ($result -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') {
        Write-Host '  → 저장 공간이 부족합니다.' -ForegroundColor Yellow
    } elseif ($result -match 'INSTALL_FAILED_VERIFICATION_FAILURE') {
        Write-Host '  → Play 프로텍트가 차단했습니다.' -ForegroundColor Yellow
        Write-Host '    Play 스토어 ▸ 프로필 ▸ Play 프로텍트 ▸ 설정 ▸ 앱 검사 끄기' -ForegroundColor Yellow
    }
    exit 1
}

# --- 접근성 서비스 켜기 (선택) ----------------------------------------------
if ($EnableAccessibility) {
    Write-Host ''
    Write-Host '  접근성 서비스를 켜는 중...' -ForegroundColor DarkGray
    $current = (& $adb shell settings get secure enabled_accessibility_services) -join '' -replace "`r|`n", ''
    if ($current -eq 'null' -or [string]::IsNullOrWhiteSpace($current)) {
        $new = $SVC
    } elseif ($current -like "*$PKG*") {
        $new = $current
    } else {
        $new = "$current`:$SVC"
    }
    & $adb shell settings put secure enabled_accessibility_services "$new" 2>&1 | Out-Null
    & $adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null

    $check = (& $adb shell settings get secure enabled_accessibility_services) -join ''
    if ($check -like "*$PKG*") {
        Write-Host '  [OK] 접근성 서비스가 켜졌습니다.' -ForegroundColor Green
        Write-Host '       (설정 화면의 "제한된 설정" 잠금을 거치지 않아도 됩니다)' -ForegroundColor DarkGray
    } else {
        Write-Host '  [!] 자동으로 켜지 못했습니다. 폰에서 직접 켜 주세요.' -ForegroundColor Yellow
        Write-Host '      설정 ▸ 접근성 ▸ 설치된 앱 ▸ IncognitoBlocker' -ForegroundColor Yellow
    }
}

# --- 앱 실행 ---------------------------------------------------------------
Write-Host ''
Write-Host '  앱을 실행합니다...' -ForegroundColor DarkGray
& $adb shell am start -n "$PKG/$PKG.ui.MainActivity" 2>&1 | Out-Null

Write-Host ''
Write-Host '  완료. 폰 화면에서 설정을 이어서 진행하세요.' -ForegroundColor Green
Write-Host ''
