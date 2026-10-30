# IncognitoBlocker

Blocks incognito / private / InPrivate / secret browsing modes.
Android, Windows, macOS, Linux and iOS — each using the mechanism that platform actually allows.
On Android it also blocks **adult sites**, by pointing the device's Private DNS at a family-filter resolver.

**[한국어 README](README.ko.md)**

---

> ### ⚠️ Read before you install this on anyone
>
> This is an accountability tool, not a surveillance tool.
>
> - **Get consent.** Installing this on an adult's device without their knowledge is illegal in many jurisdictions. The app's first screen is a consent gate for exactly this reason.
> - It always shows a persistent notification while running. That is deliberate and is not made optional.
> - It never sends anything off the device. It has no `INTERNET` permission, so it physically cannot.
> - With adult-site blocking on, though, **the device's DNS lookups (the names of the sites being opened) go to the filter you picked.** Android sends them, not the app, and only while the filter is on.
>
> If you are looking for something that watches people secretly, this is not it, and the design actively works against that use.

---

## What actually works today

Being honest about coverage matters more than a long feature list, so here is exactly what has been verified and what has not.

### Devices

| Device | OS | Result |
|---|---|---|
| **Samsung Galaxy S24+ (SM‑S926U)** | Android 16 (API 36), arm64 | ✅ Installs, accessibility service connects and watches 29 packages. Blocking not exercised end‑to‑end on this device. |
| **Pixel 8 emulator** | Android 17 (API 37), x86_64 | ✅ Full end‑to‑end: install → detect Chrome incognito → block, with no false positives |
| Anything else | Android 8.0+ (API 26+) | ⚠️ Should work, untested |

`minSdk` is 26 (Android 8.0, 2017). Nothing below API 26 is supported.

### Browsers

Detection is **rule-based**, inferred from what the browser draws on screen — Android exposes no API that says "this window is private". Rules therefore vary in confidence:

| Browser | Detection | Confidence |
|---|---|---|
| **Chrome** (+ Beta/Dev/Canary) | Incognito start page copy, tab-tray view IDs, toolbar badge | ✅ **Verified working** |
| Microsoft Edge | Chromium signals + InPrivate wording | ⚠️ Rules written, untested |
| Brave | Chromium signals + private-window wording | ⚠️ Rules written, untested |
| Vivaldi, Kiwi, Naver Whale | Chromium signals | ⚠️ Rules written, untested |
| **Samsung Internet** | Secret-mode badge IDs, "turn off secret mode" wording | ⚠️ **Least reliable — see below** |
| Firefox (+ Fenix/Beta) | Private badge, private-tab wording | ⚠️ Rules written, untested |
| Opera / Opera GX | Private-mode badge and wording | ⚠️ Rules written, untested |
| Firefox Focus, Tor Browser | Always private → blocked wholesale | ✅ By definition |
| DuckDuckGo | Fire Button signals only | ⚠️ Partial |

**Samsung Internet deserves a warning.** Samsung publishes *no* policy for disabling secret mode — the Knox `BrowserPolicy` class covers cookies, JavaScript and popups but has nothing for it. Screen detection is the only option, and the view IDs used here are empirical rather than documented. If it does not catch secret mode on your device, use **diagnostic mode** (see below) and add the strings it reports.

### Adult-site blocking

| Level | Result (Pixel 8 emulator, Android 17 / API 37, 2026-10-30) |
|---|---|
| Set by hand | ✅ With Private DNS on CleanBrowsing, `www.google.com` → `216.239.38.120` (Google's forced SafeSearch) and `www.youtube.com` → `216.239.38.119` (YouTube Restricted Mode). Switching Private DNS off turns the notification to "off" within 2 seconds |
| adb grant | ✅ Switching to automatic, off, or another host is undone within 1 second. A 10-minute pause turns itself back on when the time is up |
| Device owner | ✅ The Private DNS screen in Settings shows "Blocked by work policy", and adb's `settings put` is refused too. Pause and resume work while locked. "Remove all protection" restores the original setting, lifts the lock and drops device owner. Chrome was only seen to report "managed by your organization" — `chrome://policy` was not opened, because that means accepting Chrome's first-run terms |
| Galaxy S24+ | ⚠️ Not yet checked on the real device |

### Other platforms

| Platform | Mechanism | Status |
|---|---|---|
| Windows 10/11 | HKLM enterprise policies | ✅ Script runs; policy keys taken from vendor documentation |
| macOS / Linux | Managed Preferences / `/etc/*/policies` | ⚠️ Written, **untested** (no such machine available) |
| iOS / iPadOS | Configuration profile (`.mobileconfig`) generator | ⚠️ XML and Apple schema validated, **not installed on a device** |

---

## How the Android app works

There is no API for "is this browser in private mode", so signals drawn on screen are scored:

```
weight 2  — exists ONLY while private mode is active
            private-only view IDs, "turn off …" wording, the private start page copy
weight 1  — merely the NAME of the mode ("Secret mode", "InPrivate", …)
            which also appears in the NORMAL menu, e.g. "Turn on Secret mode"

block when the total reaches 2
```

The two tiers exist because of a real bug found during development: the first version blocked on the bare string `시크릿 모드`, which meant **opening the menu in Samsung Internet triggered a block**. A monitoring tool that cries wolf gets switched off, so a false positive is worse than a miss.

For the same reason, **web page content is not searched** by default. Reading an article about private browsing must not get you blocked. The only exception is a handful of very specific phrases a browser draws on its own private start page (Chrome renders "You've gone Incognito" as web content, so without that exception incognito tabs would never be detected at all).

Once detected: `BACK` → `HOME` → a full-screen notice. The notice uses `TYPE_ACCESSIBILITY_OVERLAY`, so no "draw over other apps" permission is needed.

**Second detection path.** Chromium browsers post a persistent notification while private tabs exist. `NotificationGuardService` watches for it, catching sessions the screen scan missed. Optional; the app works without it.

### Diagnostic mode

Detection strings change with every browser update. Rather than guessing, the app can show you what it actually reads:

1. Turn on **diagnostic mode** at the bottom of the app
2. Open private mode in the browser that is not being caught
3. Come back — the view IDs, content descriptions and text read from that screen are in the history

Add whatever you find to [`BrowserRules.kt`](android/app/src/main/java/com/incognitoblocker/core/BrowserRules.kt) and rebuild. Pull requests with confirmed signals for browsers or locales are the single most useful contribution.

---

## Adult-site blocking (Private DNS)

The app points **Private DNS** (DNS-over-TLS, Android 9+) at a family-filter resolver. Deciding which sites are adult is the resolver's job; the app carries no list of its own. DNS applies to the whole device, so **incognito tabs, Samsung Internet and in-app browsers** are covered the same way — unlike incognito detection, nothing has to be inferred from the screen.

The app still has no `INTERNET` permission. Android sends the DNS lookups to the filter itself.

### Choosing a filter

| Filter | Host | Blocks |
|---|---|---|
| **CleanBrowsing Family Filter** (default) | `family-filter-dns.cleanbrowsing.org` | Adult sites, proxy and VPN sites used to get around filters |
| AdGuard Family Protection | `family.adguard-dns.com` | Adult sites, ads, trackers |
| Cloudflare for Families | `family.cloudflare-dns.com` | Adult sites, malicious sites |

Safe-search behaviour, measured on 2026-10-30 by querying each filter's plain-DNS address directly: all three send Google, Bing and DuckDuckGo to their safe-search hosts, and **only CleanBrowsing and AdGuard put YouTube into Restricted Mode** (Cloudflare leaves YouTube alone).

### Lock levels

| Level | What happens | To turn it off |
|---|---|---|
| Set by hand | The user types the host into `Settings › Connections › More connection settings › Private DNS`; the app only reports the state | Straight from Settings — the notification then says "off" |
| **adb grant** | After a one-time `WRITE_SECURE_SETTINGS` grant over adb, the app switches the filter on itself and **puts it back the moment anything changes it** | PIN in the app |
| Device owner | The app writes it with the adb grant, then `DISALLOW_CONFIG_PRIVATE_DNS` locks the Settings screen (and refuses adb's writes); Chrome's own secure DNS is switched off | PIN in the app |

The adb grant is the strongest level that needs no factory reset.

```bash
adb shell pm grant com.incognitoblocker android.permission.WRITE_SECURE_SETTINGS
```

The first time the app changes Private DNS it **records the setting that was there**, and turning the filter off restores it (or "Automatic" when nothing was recorded).

### When the internet stops working

With Private DNS set to a host, **an unreachable filter server takes the whole connection down.** That happens on office or school Wi-Fi that blocks port 853, and on public Wi-Fi that shows a sign-in page first. When the network fails Android's internet check, the app's card shows a warning (that warning was not actually triggered on the emulator). From the adb-grant level up, **the PIN unlocks a 10-minute pause** that ends by itself — an alarm turns the filter back on even if the watchdog service has been killed (an inexact alarm, so possibly a few minutes late). At the set-by-hand level, switch Private DNS to "Automatic" in Settings instead.

iPhones are covered by Screen Time's "Limit Adult Websites", not by an app — see [iOS](#ios).

---

## Install

Download the APK from [Releases](../../releases) and open it on the phone.

Sideloading is routinely blocked, and the message you get is usually just "App not installed" with no reason. In order of likelihood:

1. **Samsung Auto Blocker** — `Settings › Security and privacy › Auto Blocker` → off. It blocks all sideloading, including over USB.
2. **A bundled antivirus.** On Korean carrier Galaxy phones, AhnLab V3 Mobile Security (`com.ahnlab.v3mobilesecurity.soda`) is preinstalled and blocks unknown APKs silently. This was the actual cause on the S24+ used for testing.
3. **Play Protect** — `Play Store › profile › Play Protect › ⚙ → turn off app scanning`. Accessibility apps are flagged aggressively.
4. **Unknown sources** — granted per-app since Android 8. Whichever app opens the APK needs it.
5. **A previous install with a different signing key** — uninstall the old copy first.

### The reliable way: adb

This bypasses every item above except Auto Blocker.

```bash
adb install -r IncognitoBlocker.apk
```

Windows users can run [`install-via-usb.ps1`](install-via-usb.ps1), which uninstalls any old copy, installs, decodes `INSTALL_FAILED_*` errors, and can enable the accessibility service directly:

```powershell
powershell -ExecutionPolicy Bypass -File install-via-usb.ps1 -EnableAccessibility
```

`-EnableAccessibility` also sidesteps Android 13+ "restricted settings", which otherwise greys out the accessibility toggle for sideloaded apps. `-GrantSecureSettings` also switches on the adb-grant level of adult-site blocking.

### After installing

1. **Allow restricted settings** — `Settings › Apps › IncognitoBlocker › ⋮ › Allow restricted settings` (Android 13+, sideloaded only)
2. **Turn on accessibility access**
3. **Set a guardian PIN** — required for pausing protection or clearing history
4. **Exempt from battery optimisation** — on Samsung One UI, skipping this means the guard is silently killed within days

---

## Strong mode (device owner)

Basic mode closes an incognito window *after* it opens. Strong mode removes incognito from Chrome and Edge entirely — the menu entry disappears.

Requires a factory reset, because a device owner can only be set while no account exists on the device:

```bash
adb shell dpm set-device-owner com.incognitoblocker/.service.AdminReceiver
```

Then press "Re-apply policies" in the app:

| Browser | Treatment |
|---|---|
| Chrome | `IncognitoModeAvailability=1` — incognito gone from the menu |
| Edge | `InPrivateModeAvailability=1` — same |
| Firefox, Samsung Internet, DuckDuckGo, Brave, Opera, … | **No policy exists** → hidden instead |
| IncognitoBlocker itself | `setUninstallBlocked` + `setUserControlDisabledPackages` |
| New browser installs | `DISALLOW_INSTALL_UNKNOWN_SOURCES` |

With adult-site blocking on, these are applied as well:

| Target | Action |
|---|---|
| Private DNS | Written with the adb grant, then `DISALLOW_CONFIG_PRIVATE_DNS` — the Settings screen and adb's `settings put` are both refused |
| Chrome's own secure DNS | `DnsOverHttpsMode=off` — closes the route of picking another resolver in Chrome's settings |

So strong mode needs the adb grant as well. Run one more line in the same adb session that sets the device owner:

```bash
adb shell pm grant com.incognitoblocker android.permission.WRITE_SECURE_SETTINGS
```

The device-owner API `setGlobalPrivateDnsModeSpecifiedHost` is deliberately not used. It checks the resolver with a TLS connection opened **inside the calling app**, and this app has no `INTERNET` permission, so the call always fails with `EPERM` (seen on the emulator). The lock (`DISALLOW_CONFIG_PRIVATE_DNS`) also refuses this app's own writes, so the app lifts it only for the moment it writes and puts it straight back.

Chrome's managed configuration is replaced as a whole bundle, so this key is written in the same bundle as `IncognitoModeAvailability`. Writing it separately would silently switch incognito blocking off.

A device owner cannot be removed from Settings. The only exit is "Remove all protection" inside the app, which needs the PIN.

> **Forget the PIN in strong mode and a factory reset is the only way out.** Write it down.

---

## Desktop

```powershell
# Windows, as Administrator
powershell -ExecutionPolicy Bypass -File desktop\Set-IncognitoBlock.ps1 -Action Apply -CloseBrowsers
```

```bash
# macOS / Linux, as root
sudo ./desktop/set-incognito-block.sh apply
```

Three things the script handles that are easy to miss:

1. **Guest profiles are incognito.** Chromium's own documentation calls a guest profile one "where all windows are in incognito mode". Blocking incognito while leaving `BrowserGuestModeEnabled` at its default achieves nothing.
2. **Allowlist policies override the block.** Chrome's `IncognitoModeUrlAllowlist` (Chrome 147+) and Edge's `InPrivateModeUrlAllowlist` take *precedence*. The script removes them.
3. **Chrome needs a restart.** `IncognitoModeAvailability` has no dynamic refresh, and Google documents that Chrome "does not close Incognito windows that are already open".

**This is void if the monitored user is a local Administrator** — they can simply delete the registry key. Move them to a Standard user account.

Browsers with **no enterprise policy at all**: Opera and Opera GX. Nothing to configure; block the executable or remove it. Vivaldi and Naver Whale have Chromium-inherited policy *names* but undocumented vendor registry paths — the script writes the likely candidates, and you should confirm at `vivaldi://policy` / `whale://policy`.

---

## iOS

```powershell
powershell -File ios\New-IncognitoBlockProfile.ps1 -RemovalPassword "something-only-you-know"
```

No Mac, no Apple Developer account, no MDM required. Send the generated `.mobileconfig` to the iPhone and install it.

The trick is that **turning on the web content filter removes Safari's Private Browsing**. That is documented behaviour, not a side effect being exploited — from Apple's own `WebContentSettings.blockedByFilter` docs: *"Setting any filter policy besides `.none` will disable Safari private browsing."*

Verified against Apple's device-management schema:

- `com.apple.webcontent-filter` — iOS 7.0+, `supervised: false`, `allowmanualinstall: true`. Since iOS 16.0 it installs on **unsupervised** iPhones provided `ContentFilterUUID` is present, which the generator always includes.
- `com.apple.applicationaccess` / `allowSafariPrivateBrowsing` — iOS **26.0+ and supervised only**. Ignored on a normal phone; included so it takes effect if the device is ever supervised.
- `com.apple.profileRemovalPassword` — requires a password to delete the profile.

**Only Safari.** Chrome, Firefox and Brave on iOS keep their own private modes. Block those apps in Screen Time or remove them.

The free alternative needs no profile at all: **Settings ▸ Screen Time ▸ Content & Privacy Restrictions ▸ Web Content ▸ Limit Adult Websites** — then set a Screen Time passcode, or it can simply be switched back.

---

## Bypasses, honestly

| Route | Countermeasure |
|---|---|
| Uninstall the app | Device admin; in strong mode `setUninstallBlocked` |
| Turn off accessibility access | The notification changes to "Protection has stopped" — it can be reported, not prevented |
| **Samsung Secure Folder** | An accessibility service cannot see inside it. **Delete Secure Folder** or accept the hole |
| Install another browser | Strong mode's `DISALLOW_INSTALL_UNKNOWN_SOURCES` |
| Opera GX | No policy exists — hide or uninstall it |
| In-app browsers (Instagram, X) | Not private mode; out of scope |
| Windows admin account | Move the user to Standard — without this the desktop side is decorative |
| Switching Private DNS off | Set by hand: the notification says "off" · adb grant: undone at once · strong mode: the Settings screen is locked |
| A browser's own secure DNS (DoH in Chrome or Firefox settings) | Strong mode switches Chrome's off. Other browsers cannot be stopped |
| Browser-built-in VPNs and proxies (Opera etc.), Tor | They resolve DNS off the device, so the filter never sees it. CleanBrowsing's family filter blocks proxy and VPN site names, and strong mode hides Opera and Tor |
| VPN apps | Whether strict Private DNS overrides a VPN's own DNS has **not been tested**. Strong mode does not restrict VPN settings either |
| Content inside social and messaging apps | DNS works per site, so whatever is posted inside an allowed app gets through |
| Wi-Fi that blocks port 853 | The internet itself stops — pause for 10 minutes with the PIN |
| A second device | Nothing here can help |

**No tool can stop a determined person.** What this actually provides is friction plus a record. For self-accountability the friction is usually enough; for a family arrangement the record is what a conversation can be built on.

---

## Known issues

From an internal audit; confirmed but not yet fixed:

- Detection runs on the main thread (600-node tree walk per event). No ANR has been observed, but it is the wrong thread for it.
- `EventLog` rewrites its whole JSON file on the callback thread.
- PBKDF2 (120k iterations) runs synchronously in the PIN dialog callback.
- PIN entry has no attempt limit or backoff.
- Strong mode has no recovery path if the PIN is lost, other than a factory reset.
- `MainActivity.onResume` re-renders everything, including ~45 PackageManager calls.
- Chromium detection after a page has loaded relies on toolbar badge view IDs that are unverified across Chrome versions; the notification path is the practical fallback.

---

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```bash
cd android
./gradlew assembleDebug          # debug-signed, fine for testing
```

For a release build, create your own signing key — the project's key is not in this repository, and `assembleRelease` deliberately fails without one rather than silently falling back to the debug key (a debug-signed "release" cannot upgrade a properly signed install, and the debug key's password is public).

```bash
keytool -genkeypair -v -keystore android/keystore/release.jks \
  -alias mykey -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# android/keystore.properties  (git-ignored)
storeFile=keystore/release.jks
storePassword=...
keyAlias=mykey
keyPassword=...
```

The app has **zero external dependencies** — framework APIs only. The APK is ~720 KB and builds offline.

---

## Privacy

- No `INTERNET` permission. Data cannot leave the device.
- With adult-site blocking on, Android sends DNS lookups (site names) to the filter you picked, encrypted with DNS-over-TLS. That company's privacy policy applies to them.
- `ACCESS_NETWORK_STATE` is used only to read which Private DNS host is in use and whether the network has internet. `WRITE_SECURE_SETTINGS` exists only when granted by hand over adb, and is used only for the two Private DNS keys (`private_dns_mode`, `private_dns_specifier`).
- `accessibility_service_config.xml` lists `packageNames` explicitly, so the service can observe **only** the listed browsers and nothing else on the device.
- Page content is read only for a handful of specific private-start-page phrases; URLs and page text are never stored.
- The log holds timestamp, browser name and which signal matched. Nothing else.
- `QUERY_ALL_PACKAGES` is not requested.

---

## License

MIT — see [LICENSE](LICENSE).

Provided as is. You are responsible for using it lawfully, which includes getting consent from anyone whose device you install it on.
