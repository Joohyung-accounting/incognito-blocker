# IncognitoBlocker

브라우저의 **시크릿 / 프라이빗 / InPrivate / 비공개 모드**를 차단합니다.
Android · Windows · macOS · Linux · iOS를 각 OS가 실제로 허용하는 방식으로 막습니다.
Android 앱은 **성인 사이트**도 막습니다 — 기기의 개인 DNS를 가족용 필터 DNS로 맞추는 방식입니다.

**[English README](README.md)**

---

> ### ⚠️ 다른 사람 기기에 설치하기 전에
>
> 이건 책임 관리 도구지 감시 도구가 아닙니다.
>
> - **반드시 동의를 받으세요.** 성인의 기기에 몰래 설치하는 것은 여러 나라에서 불법입니다. 앱 첫 화면이 동의를 받는 이유가 이것입니다.
> - 실행 중에는 항상 상단 알림을 띄웁니다. 의도된 동작이고 끌 수 없습니다.
> - 어떤 데이터도 기기 밖으로 나가지 않습니다. `INTERNET` 권한이 없어서 물리적으로 불가능합니다.
> - 단, 성인 사이트 차단을 켜면 **기기의 DNS 조회(접속하려는 사이트 이름)가 고른 필터 회사로 갑니다.** 앱이 아니라 Android가 보내는 것이고, 켰을 때만 그렇습니다.
>
> 몰래 감시하는 도구를 찾고 계신 거라면 이 앱은 아닙니다. 설계 자체가 그런 용도를 막고 있습니다.

---

## 지금 실제로 되는 것

기능 목록을 길게 늘어놓기보다 **검증된 것과 안 된 것**을 정확히 적는 게 맞다고 봅니다.

### 기기

| 기기 | OS | 결과 |
|---|---|---|
| **갤럭시 S24+ (SM‑S926U)** | Android 16 (API 36), arm64 | ✅ 설치 성공, 접근성 서비스 연결·29개 패키지 감시 확인. 차단 동작 자체는 이 기기에서 끝까지 확인하지 않음 |
| **Pixel 8 에뮬레이터** | Android 17 (API 37), x86_64 | ✅ 전 과정 검증: 설치 → Chrome 시크릿 탭 감지 → 차단, 오탐 0건 |
| 그 외 | Android 8.0+ (API 26+) | ⚠️ 될 것으로 보이나 미검증 |

`minSdk`는 26 (Android 8.0)입니다. 그 이하는 지원하지 않습니다.

### 브라우저

Android에는 "이 창이 시크릿인가"를 알려주는 API가 없습니다. 그래서 화면에 그려진 신호로 **추론**하며, 브라우저마다 신뢰도가 다릅니다.

| 브라우저 | 감지 방식 | 신뢰도 |
|---|---|---|
| **Chrome** (+베타/개발자/카나리) | 시크릿 시작 페이지 문구, 탭 트레이 뷰 ID, 툴바 배지 | ✅ **검증 완료** |
| Microsoft Edge | Chromium 신호 + InPrivate 문구 | ⚠️ 규칙 작성됨, 미검증 |
| Brave | Chromium 신호 + 비공개 창 문구 | ⚠️ 규칙 작성됨, 미검증 |
| Vivaldi, Kiwi, 네이버 웨일 | Chromium 신호 | ⚠️ 규칙 작성됨, 미검증 |
| **삼성 인터넷** | 시크릿 배지 ID, "시크릿 모드 끄기" 문구 | ⚠️ **신뢰도 가장 낮음 — 아래 참고** |
| Firefox (+Fenix/베타) | 사생활 보호 배지·문구 | ⚠️ 규칙 작성됨, 미검증 |
| Opera / Opera GX | 비공개 모드 배지·문구 | ⚠️ 규칙 작성됨, 미검증 |
| Firefox Focus, Tor Browser | 항상 프라이빗 → 앱 전체 차단 | ✅ 정의상 확실 |
| DuckDuckGo | Fire Button 신호만 | ⚠️ 부분적 |

**삼성 인터넷은 따로 경고가 필요합니다.** 삼성은 시크릿 모드를 끄는 정책을 **공개하지 않습니다.** Knox의 `BrowserPolicy` 클래스에도 쿠키·자바스크립트·팝업만 있고 시크릿 모드 항목이 없습니다. 화면 감지가 유일한 방법이고, 여기 쓰인 뷰 ID는 문서화된 값이 아니라 경험적으로 얻은 값입니다. 내 기기에서 안 잡히면 **진단 모드**로 실제 문자열을 확인해 추가하세요.

### 성인 사이트 차단

| 단계 | 결과 (Pixel 8 에뮬레이터, Android 17 / API 37, 2026-10-30) |
|---|---|
| 직접 설정 | ✅ 개인 DNS를 CleanBrowsing으로 맞추자 `www.google.com` → `216.239.38.120`(구글 강제 안전 검색), `www.youtube.com` → `216.239.38.119`(유튜브 제한 모드). 개인 DNS를 끄면 2초 안에 알림이 "꺼짐"으로 바뀜 |
| adb 권한 | ✅ 자동 · 끔 · 다른 호스트로 바꿔도 1초 안에 되돌림. 10분 일시 중지 → 시간이 지나면 저절로 다시 켜짐 |
| 기기 소유자 | ✅ 설정의 개인 DNS 화면이 "관리자가 차단"으로 잠기고 adb의 `settings put`도 거부됨. 잠긴 채 일시 중지·재개 동작. "보호 완전 해제"로 원래 설정 복원 + 잠금 해제 + device owner 해제. Chrome은 "조직에서 관리" 표시까지만 확인 — `chrome://policy`는 Chrome 첫 실행 약관 화면 때문에 보지 않음 |
| 갤럭시 S24+ | ⚠️ 실기에서는 아직 확인하지 않음 |

### 그 외 플랫폼

| 플랫폼 | 방식 | 상태 |
|---|---|---|
| Windows 10/11 | HKLM 엔터프라이즈 정책 | ✅ 스크립트 실행 확인, 정책 키는 벤더 문서 기준 |
| macOS / Linux | Managed Preferences / `/etc/*/policies` | ⚠️ 작성됨, **미검증** (해당 기기 없음) |
| iOS / iPadOS | 구성 프로필(`.mobileconfig`) 생성기 | ⚠️ XML·Apple 스키마 검증, **실기 설치는 미검증** |

---

## Android 앱 동작 방식

화면 신호를 점수로 매깁니다.

```
가중치 2 — 시크릿 모드일 때만 존재하는 것
           시크릿 전용 뷰 ID, "끄기" 문구, 시크릿 시작 페이지 안내
가중치 1 — 모드 '이름' 그 자체 ("시크릿 모드", "InPrivate" …)
           일반 모드 메뉴에도 그대로 있음 ("시크릿 모드 켜기")

합계 2점 이상일 때만 차단
```

이렇게 나눈 건 실제로 겪은 버그 때문입니다. 초기 버전은 `시크릿 모드`라는 문자열만 보고 차단했는데, 그 결과 **삼성 인터넷에서 메뉴만 열어도 차단이 터졌습니다.** 감시 도구는 오탐이 나는 순간 사용자가 꺼버리므로, **오탐은 미탐보다 나쁩니다.**

같은 이유로 **웹페이지 본문은 기본적으로 검색하지 않습니다.** 시크릿 모드를 설명하는 글을 읽는다고 차단당하면 안 되니까요. 예외는 브라우저가 자기 시크릿 시작 페이지에만 그리는 소수의 구체적 문구뿐입니다. (Chrome은 "You've gone Incognito"를 웹 콘텐츠로 렌더링하기 때문에, 이 예외가 없으면 시크릿 탭을 아예 감지하지 못합니다.)

감지되면 `뒤로가기` → `홈` → 전체화면 안내 순으로 처리합니다. 안내 화면은 `TYPE_ACCESSIBILITY_OVERLAY`를 쓰므로 '다른 앱 위에 표시' 권한이 필요 없습니다.

**두 번째 감지 경로.** Chromium 계열은 시크릿 탭이 열려 있는 동안 고정 알림을 띄웁니다. `NotificationGuardService`가 이걸 잡아 화면 감지가 놓친 세션을 보완합니다. 선택 사항입니다.

### 진단 모드

감지 문자열은 브라우저가 업데이트될 때마다 바뀝니다. 추측하지 말고 실제로 읽히는 값을 보세요.

1. 앱 하단에서 **진단 모드**를 켭니다
2. 안 잡히는 브라우저에서 시크릿 모드를 엽니다
3. 앱으로 돌아오면 그 화면의 뷰 ID · 콘텐츠 설명 · 텍스트가 기록에 남습니다

찾은 값을 [`BrowserRules.kt`](android/app/src/main/java/com/incognitoblocker/core/BrowserRules.kt)에 추가하고 다시 빌드하면 됩니다. 브라우저나 언어별로 확인된 신호를 PR로 보내주시는 게 가장 도움이 됩니다.

---

## 성인 사이트 차단 (개인 DNS)

Android 9부터 있는 **개인 DNS**(DNS-over-TLS)를 가족용 필터 DNS로 맞춥니다. 어떤 사이트가 성인 사이트인지는 필터 회사가 판단하고, 이 앱에는 목록이 없습니다. DNS는 기기 전체에 걸리므로 **시크릿 탭, 삼성 인터넷, 앱 안의 브라우저까지** 똑같이 막힙니다 — 시크릿 모드 감지와 달리 화면을 추론할 필요가 없습니다.

앱은 여전히 `INTERNET` 권한이 없습니다. DNS 조회는 Android가 직접 필터 서버로 보냅니다.

### 필터 고르기

| 필터 | 호스트 | 막는 것 |
|---|---|---|
| **CleanBrowsing 가족 필터** (기본) | `family-filter-dns.cleanbrowsing.org` | 성인 사이트, 우회용 프록시·VPN 사이트 |
| AdGuard 가족 보호 | `family.adguard-dns.com` | 성인 사이트, 광고, 추적기 |
| Cloudflare 가족용 | `family.cloudflare-dns.com` | 성인 사이트, 악성 사이트 |

2026-10-30에 각 필터의 일반 DNS 주소로 직접 조회해 확인한 안전 검색 동작: 셋 다 구글·빙·덕덕고를 안전 검색 주소로 돌리고, **유튜브 제한 모드는 CleanBrowsing과 AdGuard만** 겁니다(Cloudflare는 유튜브를 그대로 둠).

### 잠금 단계

| 단계 | 하는 일 | 끄려면 |
|---|---|---|
| 직접 설정 | 사용자가 `설정 › 연결 › 기타 연결 설정 › 개인 DNS`에 호스트를 넣고, 앱은 상태만 알림 | 설정에서 바로 끌 수 있음 — 꺼지면 상단 알림이 "꺼짐"으로 바뀜 |
| **adb 권한** | `WRITE_SECURE_SETTINGS`를 adb로 한 번 주면 앱이 직접 켜고, **누가 바꾸면 즉시 되돌림** | 앱에서 PIN |
| 기기 소유자 | adb 권한으로 앱이 쓴 뒤 `DISALLOW_CONFIG_PRIVATE_DNS`로 설정 화면을 잠금(adb의 쓰기도 거부됨), Chrome 자체 보안 DNS도 끔 | 앱에서 PIN |

adb 권한은 공장초기화 없이 쓸 수 있는 가장 강한 단계입니다.

```bash
adb shell pm grant com.incognitoblocker android.permission.WRITE_SECURE_SETTINGS
```

앱이 개인 DNS를 처음 바꿀 때 **원래 설정을 기록해 두고**, 끌 때 그 설정으로 되돌립니다(기록이 없으면 '자동').

### 인터넷이 끊기면

개인 DNS를 호스트로 지정하면 **필터 서버에 닿지 못할 때 인터넷 전체가 끊깁니다.** 포트 853을 막는 회사·학교 와이파이나, 로그인 페이지가 먼저 뜨는 공용 와이파이에서 그렇습니다. 네트워크가 Android의 인터넷 확인에 실패하면 앱 카드에 경고가 뜹니다(이 경고는 에뮬레이터에서 실제로 띄워 보지는 못했습니다). adb 권한 이상이면 **PIN을 넣고 10분 일시 중지**할 수 있고, 10분이 지나면 저절로 다시 켜집니다 — 감시 서비스가 죽어 있어도 알람이 다시 켭니다(정확한 알람이 아니라 최대 몇 분 늦을 수 있음). 직접 설정 단계에서는 설정에서 개인 DNS를 '자동'으로 바꾸면 됩니다.

iPhone은 앱이 아니라 스크린 타임의 "성인 웹사이트 제한"으로 막습니다 — [iOS](#ios) 참고.

---

## 설치

[Releases](../../releases)에서 APK를 받아 폰에서 여세요.

사이드로드는 자주 막히는데, **이유 없이 "앱이 설치되지 않았습니다"만 뜹니다.** 가능성 순서대로:

1. **삼성 자동 차단** — `설정 › 보안 및 개인정보 보호 › 자동 차단` → 끄기. USB 설치까지 막습니다.
2. **기본 탑재 백신.** 한국 통신사 갤럭시에는 AhnLab V3 모바일 시큐리티(`com.ahnlab.v3mobilesecurity.soda`)가 깔려 있고, 알 수 없는 APK를 조용히 막습니다. 테스트에 쓴 S24+에서 실제 원인이었습니다.
3. **Play 프로텍트** — `Play 스토어 › 프로필 › Play 프로텍트 › ⚙ → 앱 검사 끄기`. 접근성 앱은 특히 강하게 막힙니다.
4. **알 수 없는 출처** — Android 8부터 앱별로 허용해야 합니다. APK를 여는 앱에 허용이 필요합니다.
5. **서명 키가 다른 구버전이 설치돼 있음** — 먼저 삭제하세요.

### 확실한 방법: adb

자동 차단을 제외한 위 항목을 전부 우회합니다.

```bash
adb install -r IncognitoBlocker.apk
```

Windows에서는 [`install-via-usb.ps1`](install-via-usb.ps1)을 쓰면 구버전 삭제·설치·`INSTALL_FAILED_*` 원인 해석까지 해줍니다.

```powershell
powershell -ExecutionPolicy Bypass -File install-via-usb.ps1 -EnableAccessibility
```

`-EnableAccessibility`를 붙이면 Android 13+의 "제한된 설정" 잠금까지 건너뜁니다. `-GrantSecureSettings`를 붙이면 성인 사이트 차단의 adb 권한 단계까지 켜집니다.

### 설치 후

1. **제한된 설정 허용** — `설정 › 애플리케이션 › IncognitoBlocker › ⋮ › 제한된 설정 허용` (Android 13+, 사이드로드만)
2. **접근성 권한 켜기**
3. **보호자 PIN 설정** — 차단 해제·기록 삭제에 필요
4. **배터리 최적화 제외** — 삼성 One UI에서 이걸 안 하면 며칠 안에 조용히 죽습니다

---

## 강력 모드 (device owner)

기본 모드는 시크릿 창을 **열린 뒤** 닫습니다. 강력 모드는 Chrome·Edge에서 시크릿 모드를 아예 없앱니다.

계정이 하나도 없는 상태에서만 device owner를 지정할 수 있어 **공장초기화가 필요합니다.**

```bash
adb shell dpm set-device-owner com.incognitoblocker/.service.AdminReceiver
```

이후 앱에서 "정책 다시 적용"을 누르면:

| 브라우저 | 처리 |
|---|---|
| Chrome | `IncognitoModeAvailability=1` — 메뉴에서 항목 자체가 사라짐 |
| Edge | `InPrivateModeAvailability=1` — 동일 |
| Firefox, 삼성 인터넷, DuckDuckGo, Brave, Opera 등 | **정책이 없음** → 숨김 처리 |
| IncognitoBlocker 자신 | `setUninstallBlocked` + `setUserControlDisabledPackages` |
| 새 브라우저 설치 | `DISALLOW_INSTALL_UNKNOWN_SOURCES` |

성인 사이트 차단을 켜 두면 다음도 함께 걸립니다.

| 대상 | 처리 |
|---|---|
| 개인 DNS | adb 권한으로 쓴 뒤 `DISALLOW_CONFIG_PRIVATE_DNS` — 설정 화면도 adb의 `settings put`도 거부됨 |
| Chrome 자체 보안 DNS | `DnsOverHttpsMode=off` — Chrome 설정에서 다른 DNS를 골라 우회하는 길을 막음 |

그래서 강력 모드에서도 adb 권한이 필요합니다. device owner를 지정하는 그 adb에서 한 줄 더 실행하세요.

```bash
adb shell pm grant com.incognitoblocker android.permission.WRITE_SECURE_SETTINGS
```

device owner 전용 API인 `setGlobalPrivateDnsModeSpecifiedHost`는 쓰지 않습니다. 이 API는 **호출한 앱 안에서** TLS 연결을 열어 필터 서버를 확인하는데, 이 앱은 `INTERNET` 권한이 없어서 항상 `EPERM`으로 실패합니다(에뮬레이터에서 확인). 그리고 잠금(`DISALLOW_CONFIG_PRIVATE_DNS`)은 이 앱 자신의 쓰기도 거부하므로, 앱은 쓸 때만 잠금을 잠깐 풀었다가 바로 다시 겁니다.

Chrome의 관리 구성은 묶음째로 교체되므로, 이 키는 `IncognitoModeAvailability`와 같은 묶음에 함께 씁니다. 따로 쓰면 시크릿 차단이 조용히 풀립니다.

device owner는 설정에서 해제할 수 없습니다. 유일한 해제 경로는 앱 안의 "보호 완전 해제"(PIN 필요)입니다.

> **강력 모드에서 PIN을 잊으면 공장초기화 말고 방법이 없습니다.** 반드시 적어 두세요.

---

## 데스크톱

```powershell
# Windows, 관리자 권한
powershell -ExecutionPolicy Bypass -File desktop\Set-IncognitoBlock.ps1 -Action Apply -CloseBrowsers
```

```bash
# macOS / Linux, root
sudo ./desktop/set-incognito-block.sh apply
```

놓치기 쉬운 세 가지를 스크립트가 함께 처리합니다.

1. **게스트 프로필이 곧 시크릿입니다.** Chromium 공식 문서가 게스트 프로필을 "모든 창이 시크릿 모드인 프로필"이라고 정의합니다. `BrowserGuestModeEnabled`를 기본값으로 두면 시크릿 차단은 무의미합니다.
2. **allowlist 정책이 차단을 무효화합니다.** Chrome의 `IncognitoModeUrlAllowlist`(147+)와 Edge의 `InPrivateModeUrlAllowlist`가 **우선**합니다. 스크립트가 이를 제거합니다.
3. **Chrome은 재시작이 필요합니다.** `IncognitoModeAvailability`는 동적 갱신을 지원하지 않고, 이미 열린 시크릿 창은 닫히지 않는다고 구글이 명시합니다.

**차단 대상이 로컬 관리자면 전부 무의미합니다** — 레지스트리 키를 그냥 지우면 되니까요. 반드시 표준 사용자로 전환하세요.

**엔터프라이즈 정책이 아예 없는 브라우저**: Opera, Opera GX. 설정할 게 없으므로 실행 차단이나 삭제만 가능합니다. Vivaldi와 네이버 웨일은 정책 *이름*은 Chromium 상속이지만 벤더 레지스트리 경로가 문서화돼 있지 않습니다. 스크립트가 후보 경로를 기록하니 `vivaldi://policy` / `whale://policy`에서 직접 확인하세요.

---

## iOS

```powershell
powershell -File ios\New-IncognitoBlockProfile.ps1 -RemovalPassword "나만아는암호"
```

Mac도, Apple 개발자 계정도, MDM도 필요 없습니다. 생성된 `.mobileconfig`를 아이폰에 보내 설치하면 됩니다.

핵심은 **웹 콘텐츠 필터를 켜면 Safari의 프라이빗 브라우징이 사라진다**는 점입니다. 편법이 아니라 Apple이 문서화한 동작입니다 — `WebContentSettings.blockedByFilter` 문서 원문: *"Setting any filter policy besides `.none` will disable Safari private browsing."*

Apple 기기관리 스키마로 확인한 사실:

- `com.apple.webcontent-filter` — iOS 7.0+, `supervised: false`, `allowmanualinstall: true`. iOS 16.0부터 `ContentFilterUUID`만 있으면 **감독되지 않은 일반 아이폰에도** 설치됩니다. 생성기가 항상 넣습니다.
- `com.apple.applicationaccess` / `allowSafariPrivateBrowsing` — iOS **26.0+ 이면서 감독 기기 전용**. 일반 폰에서는 무시되지만, 나중에 감독 기기가 되면 자동으로 효력이 생깁니다.
- `com.apple.profileRemovalPassword` — 프로필 삭제에 비밀번호를 요구합니다.

**Safari만 막힙니다.** iOS의 Chrome·Firefox·Brave는 자체 프라이빗 모드를 유지합니다. 스크린 타임에서 그 앱들을 막거나 삭제해야 합니다.

프로필 없이 무료로 하는 방법: **설정 ▸ 스크린 타임 ▸ 콘텐츠 및 개인 정보 보호 제한 ▸ 웹 콘텐츠 ▸ 성인 웹사이트 제한** — 그리고 **스크린 타임 암호를 반드시 설정**하세요. 없으면 그냥 되돌릴 수 있습니다.

---

## 우회 경로, 솔직하게

| 방법 | 대응 |
|---|---|
| 앱 삭제 | 기기 관리자 등록, 강력 모드에선 `setUninstallBlocked` |
| 접근성 권한 끄기 | 알림이 "보호가 중단되었습니다"로 바뀜 — 막을 수는 없고 알릴 수만 있음 |
| **삼성 보안 폴더** | 접근성 서비스가 안을 못 봄. **보안 폴더를 삭제**하거나 구멍을 감수해야 함 |
| 새 브라우저 설치 | 강력 모드의 `DISALLOW_INSTALL_UNKNOWN_SOURCES` |
| Opera GX | 정책이 없음 — 숨기거나 삭제 |
| 인스타·X 인앱 브라우저 | 시크릿 모드가 아님, 대상 밖 |
| Windows 관리자 계정 | 표준 사용자로 전환 — 안 하면 데스크톱 차단은 장식 |
| 개인 DNS 끄기 | 직접 설정: 알림이 "꺼짐"으로 바뀜 · adb 권한: 즉시 되돌림 · 강력 모드: 설정 화면이 잠김 |
| 브라우저 자체 보안 DNS (Chrome·Firefox 설정의 DoH) | 강력 모드에서 Chrome만 끔. 다른 브라우저는 못 막음 |
| 브라우저 내장 VPN·프록시 (Opera 등), Tor | DNS를 기기 밖에서 풀어서 못 막음. CleanBrowsing 가족 필터는 프록시·VPN 사이트 이름 자체를 막고, 강력 모드는 Opera·Tor를 숨김 |
| VPN 앱 | 엄격 모드 개인 DNS가 VPN의 DNS보다 우선하는지는 **확인하지 않음**. 강력 모드도 VPN 설정은 막지 않음 |
| SNS·메신저 앱 안의 콘텐츠 | DNS는 사이트 단위라, 허용된 앱 안에 올라온 것은 못 막음 |
| 포트 853을 막는 와이파이 | 인터넷 자체가 끊김 — PIN으로 10분 일시 중지 |
| 다른 기기 사용 | 막을 방법 없음 |

**마음먹은 사람을 기술로 막을 수는 없습니다.** 이 앱이 실제로 주는 건 **마찰과 기록**입니다. 자기 통제용이라면 마찰만으로 충분하고, 가족 간 약속이라면 기록이 대화의 근거가 됩니다.

---

## 알려진 문제

내부 감사에서 확인됐지만 아직 고치지 않은 것들입니다.

- 감지가 메인 스레드에서 돕니다 (이벤트마다 최대 600노드 순회). ANR은 관측되지 않았지만 적절한 스레드는 아닙니다.
- `EventLog`가 콜백 스레드에서 JSON 파일 전체를 다시 씁니다.
- PBKDF2(12만 회)가 PIN 다이얼로그 콜백에서 동기 실행됩니다.
- PIN 입력에 시도 횟수 제한이나 지연이 없습니다.
- 강력 모드에서 PIN 분실 시 공장초기화 외 복구 경로가 없습니다.
- `MainActivity.onResume`가 화면 전체를 다시 그립니다 (PackageManager 호출 약 45회 포함).
- 페이지 로드 후의 Chromium 감지는 툴바 배지 뷰 ID에 의존하는데 버전별 검증이 안 돼 있습니다. 실질적 보완은 알림 경로입니다.

---

## 빌드

JDK 17+ 와 Android SDK(compileSdk 36)가 필요합니다.

```bash
cd android
./gradlew assembleDebug          # debug 서명, 테스트용으로 충분
```

릴리스 빌드는 **본인 서명 키를 직접 만들어야 합니다.** 이 저장소에는 키가 없고, `assembleRelease`는 키가 없으면 조용히 debug 키로 떨어지지 않고 **일부러 실패합니다.** (debug 서명된 "release"는 제대로 서명된 설치본을 덮어쓸 수 없고, debug 키는 비밀번호가 공개돼 있습니다.)

```bash
keytool -genkeypair -v -keystore android/keystore/release.jks \
  -alias mykey -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# android/keystore.properties  (git 제외됨)
storeFile=keystore/release.jks
storePassword=...
keyAlias=mykey
keyPassword=...
```

**외부 의존성이 0개입니다** — 프레임워크 API만 씁니다. APK는 약 720 KB이고 오프라인에서도 빌드됩니다.

---

## 프라이버시

- `INTERNET` 권한이 없습니다. 데이터가 기기 밖으로 나갈 수 없습니다.
- 성인 사이트 차단을 켜면 Android가 DNS 조회(사이트 이름)를 고른 필터 회사로 보냅니다(DNS-over-TLS로 암호화). 그 회사의 개인정보 정책이 적용됩니다.
- `ACCESS_NETWORK_STATE`는 지금 쓰는 개인 DNS와 인터넷 연결 여부를 읽는 데만 씁니다. `WRITE_SECURE_SETTINGS`는 adb로 직접 줄 때만 생기고, 개인 DNS 두 키(`private_dns_mode`, `private_dns_specifier`)만 씁니다.
- `accessibility_service_config.xml`에 `packageNames`를 명시해서, 이 서비스는 **목록에 있는 브라우저 외에는 아무것도 볼 수 없습니다.**
- 페이지 내용은 시크릿 시작 페이지의 특정 문구 몇 개를 찾을 때만 읽고, URL이나 본문은 저장하지 않습니다.
- 기록에 남는 건 시각 · 브라우저 이름 · 어떤 신호에 걸렸는지뿐입니다.
- `QUERY_ALL_PACKAGES`를 요청하지 않습니다.

---

## 라이선스

MIT — [LICENSE](LICENSE) 참고.

있는 그대로 제공됩니다. 합법적으로 사용할 책임은 사용자에게 있으며, 여기에는 설치 대상자의 동의를 받는 것이 포함됩니다.
