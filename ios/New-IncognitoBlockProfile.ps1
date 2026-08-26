#Requires -Version 5.1
<#
.SYNOPSIS
    iPhone / iPad 용 프라이빗 브라우징 차단 구성 프로필(.mobileconfig)을 만듭니다.

.DESCRIPTION
    Mac 도, Apple 개발자 계정도, MDM 도 없이 Windows PC 에서 만들 수 있습니다.
    만들어진 파일을 아이폰으로 보내 설치하면 됩니다.

    ── 무엇이 들어가는가 ──────────────────────────────────────────

    1) com.apple.webcontent-filter  (핵심)
       FilterType = BuiltIn + AutoFilterEnabled = true
       -> iOS 내장 성인 콘텐츠 필터를 켭니다.
          이 필터가 켜지면 Safari 의 '개인정보 보호 모드(Private Browsing)' 가 사라집니다.
          Apple 문서 원문: "Setting any filter policy besides .none will disable
          Safari private browsing." (같은 필터를 프로필로 켜는 것)

       Apple 스키마 확인 결과 (2026-08 기준):
          iOS 7.0+ / supervised: false / allowmanualinstall: true
          "As of iOS 16.0 ... this can be installed on unsupervised devices and
           user enrollments if ContentFilterUUID is specified."
       -> 그래서 ContentFilterUUID 를 항상 넣습니다.

    2) com.apple.applicationaccess
       allowSafariPrivateBrowsing = false
       -> 프라이빗 브라우징을 직접 끄는 유일한 전용 키입니다.
          다만 iOS 26.0+ 이고 '감독(supervised)' 기기여야 적용됩니다.
          일반 소비자 아이폰에서는 무시되므로, 넣어 두어도 해가 없고
          나중에 감독 기기가 되면 자동으로 효력이 생깁니다.

       allowCloudPrivateRelay = false
       -> iCloud 비공개 릴레이가 켜져 있으면 필터링이 무력화될 수 있습니다.
          (이 키도 supervised 필요)

    3) com.apple.profileRemovalPassword
       RemovalPassword
       -> 프로필을 지우려면 비밀번호를 입력해야 합니다.
          수동 설치 프로필의 최대 약점(그냥 삭제 가능)을 보완합니다.

    ── 솔직한 한계 ────────────────────────────────────────────────
    · Safari 만 해당됩니다. Chrome/Firefox/Brave 의 iOS 앱은 자체 프라이빗 모드를
      그대로 유지합니다. 그 브라우저들은 스크린 타임에서 앱 자체를 막아야 합니다.
    · 감독 기기가 아니면 사용자가 프로필을 지울 수 있습니다.
      RemovalPassword 가 그 문턱을 높여 줄 뿐 완전한 잠금은 아닙니다.
    · 반드시 실제 기기에서 설치 후 확인하세요.
      확인법: Safari > 탭 버튼 > 하단 탭 그룹 목록에 '개인정보 보호' 가 사라졌는지 봅니다.

.PARAMETER RemovalPassword
    프로필 삭제 시 요구할 비밀번호. 감시받는 사람이 모르는 값으로 정하세요.

.PARAMETER OutputPath
    저장할 .mobileconfig 경로. 기본값은 이 스크립트 옆의 IncognitoBlocker.mobileconfig

.PARAMETER BlockedDomains
    추가로 차단할 도메인 목록 (최대 500개). 생략 가능.

.EXAMPLE
    .\New-IncognitoBlockProfile.ps1 -RemovalPassword "부모만아는암호"

.EXAMPLE
    .\New-IncognitoBlockProfile.ps1 -RemovalPassword "1234pw" -BlockedDomains "example.com","test.com"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RemovalPassword,

    [string]$OutputPath,

    [string[]]$BlockedDomains = @(),

    [string]$OrganizationName = 'IncognitoBlocker'
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $PSScriptRoot 'IncognitoBlocker.mobileconfig'
}

if ($BlockedDomains.Count -gt 500) {
    throw 'DenyListURLs 는 최대 500개까지만 허용됩니다 (Apple 제한).'
}

function New-Uuid { [guid]::NewGuid().ToString().ToUpper() }

function ConvertTo-XmlText {
    param([string]$Value)
    return [System.Security.SecurityElement]::Escape($Value)
}

$profileUuid  = New-Uuid
$filterUuid   = New-Uuid
$contentUuid  = New-Uuid
$accessUuid   = New-Uuid
$removalUuid  = New-Uuid

$baseId = 'com.incognitoblocker.profile'

# --- 1) 웹 콘텐츠 필터 (실제로 프라이빗 브라우징을 죽이는 부분) ---------------
$denyListXml = ''
if ($BlockedDomains.Count -gt 0) {
    $items = ($BlockedDomains | ForEach-Object { "                <string>$(ConvertTo-XmlText $_)</string>" }) -join "`n"
    $denyListXml = @"
            <key>DenyListURLs</key>
            <array>
$items
            </array>
"@
}

$filterPayload = @"
        <dict>
            <key>PayloadType</key>
            <string>com.apple.webcontent-filter</string>
            <key>PayloadIdentifier</key>
            <string>$baseId.webcontentfilter</string>
            <key>PayloadUUID</key>
            <string>$filterUuid</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
            <key>PayloadDisplayName</key>
            <string>웹 콘텐츠 필터</string>
            <key>PayloadDescription</key>
            <string>성인 콘텐츠 자동 필터를 켭니다. 이 필터가 켜지면 Safari 의 개인정보 보호 모드를 사용할 수 없습니다.</string>
            <key>PayloadEnabled</key>
            <true/>
            <key>FilterType</key>
            <string>BuiltIn</string>
            <key>AutoFilterEnabled</key>
            <true/>
            <key>ContentFilterUUID</key>
            <string>$contentUuid</string>
$denyListXml
        </dict>
"@

# --- 2) 제한 (감독 기기 + iOS 26 이상에서만 효력) --------------------------
$accessPayload = @"
        <dict>
            <key>PayloadType</key>
            <string>com.apple.applicationaccess</string>
            <key>PayloadIdentifier</key>
            <string>$baseId.restrictions</string>
            <key>PayloadUUID</key>
            <string>$accessUuid</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
            <key>PayloadDisplayName</key>
            <string>Safari 제한</string>
            <key>PayloadDescription</key>
            <string>Safari 개인정보 보호 모드를 직접 차단합니다. iOS 26 이상, 감독(supervised) 기기에서만 적용됩니다.</string>
            <key>PayloadEnabled</key>
            <true/>
            <key>allowSafariPrivateBrowsing</key>
            <false/>
            <key>allowCloudPrivateRelay</key>
            <false/>
        </dict>
"@

# --- 3) 삭제 비밀번호 -------------------------------------------------------
$removalPayload = @"
        <dict>
            <key>PayloadType</key>
            <string>com.apple.profileRemovalPassword</string>
            <key>PayloadIdentifier</key>
            <string>$baseId.removalpassword</string>
            <key>PayloadUUID</key>
            <string>$removalUuid</string>
            <key>PayloadVersion</key>
            <integer>1</integer>
            <key>PayloadDisplayName</key>
            <string>프로필 삭제 비밀번호</string>
            <key>PayloadEnabled</key>
            <true/>
            <key>RemovalPassword</key>
            <string>$(ConvertTo-XmlText $RemovalPassword)</string>
        </dict>
"@

$xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>PayloadContent</key>
    <array>
$filterPayload
$accessPayload
$removalPayload
    </array>
    <key>PayloadDisplayName</key>
    <string>IncognitoBlocker - 프라이빗 브라우징 차단</string>
    <key>PayloadDescription</key>
    <string>Safari 의 개인정보 보호 모드를 사용할 수 없게 합니다. 방문 기록은 기기 안에만 남으며 어디로도 전송되지 않습니다.</string>
    <key>PayloadIdentifier</key>
    <string>$baseId</string>
    <key>PayloadOrganization</key>
    <string>$(ConvertTo-XmlText $OrganizationName)</string>
    <key>PayloadType</key>
    <string>Configuration</string>
    <key>PayloadUUID</key>
    <string>$profileUuid</string>
    <key>PayloadVersion</key>
    <integer>1</integer>
    <key>PayloadRemovalDisallowed</key>
    <true/>
</dict>
</plist>
"@

# BOM 없는 UTF-8 로 저장해야 iOS 가 정상적으로 읽는다
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutputPath, $xml, $utf8NoBom)

Write-Host ''
Write-Host '  IncognitoBlocker - iOS 구성 프로필 생성 완료' -ForegroundColor Cyan
Write-Host '  ------------------------------------------' -ForegroundColor DarkGray
Write-Host ''
Write-Host "  파일: $OutputPath" -ForegroundColor Green
Write-Host "  크기: $((Get-Item $OutputPath).Length) bytes"
Write-Host ''
Write-Host '  설치 방법' -ForegroundColor Yellow
Write-Host '   1. 이 파일을 아이폰으로 보냅니다 (AirDrop / 메일 첨부 / iCloud Drive)'
Write-Host '   2. 아이폰에서 파일을 엽니다'
Write-Host '   3. 설정 앱 상단에 "프로파일이 다운로드됨" 이 뜹니다 - 눌러서 설치'
Write-Host '   4. 기기 암호를 입력하고 "설치" 를 두 번 누릅니다'
Write-Host ''
Write-Host '  확인 방법' -ForegroundColor Yellow
Write-Host '   Safari 를 열고 탭 버튼(오른쪽 아래 사각형 두 개)을 누릅니다.'
Write-Host '   하단 탭 그룹 목록에서 "개인정보 보호" 가 사라졌으면 성공입니다.'
Write-Host ''
Write-Host '  꼭 알아 두세요' -ForegroundColor Red
Write-Host '   · Safari 만 막힙니다. Chrome / Firefox / Brave 앱의 시크릿 모드는 그대로입니다.'
Write-Host '     -> 스크린 타임에서 그 앱들을 아예 차단하거나 삭제해야 합니다.'
Write-Host '   · 감독(supervised) 기기가 아니면 프로필을 지울 수 있습니다.'
Write-Host '     삭제 비밀번호가 문턱을 높여 줄 뿐, 완전한 잠금은 아닙니다.'
Write-Host '   · 설치 후 실제로 개인정보 보호 모드가 사라졌는지 반드시 눈으로 확인하세요.'
Write-Host ''
