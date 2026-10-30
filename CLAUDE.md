# IncognitoBlocker — project instructions

Blocks incognito / private / InPrivate browsing on Android, Windows, macOS,
Linux and iOS, each through the mechanism that platform actually allows. On
Android it also blocks adult sites by pointing Private DNS at a family-filter
resolver (`core/SafeDns.kt`) — the OS sends the lookups, the app still sends
nothing.

**This is an accountability tool, not a surveillance tool, and the code is built
to keep it that way.** The consent gate on first launch, the persistent
notification while running, and the absence of the `INTERNET` permission are
design commitments. Do not weaken any of them and do not add a network call —
the app physically cannot send anything off the device today, and that is the point.

## Layout

```
android/                 the main path (the owner's phone is a Galaxy S24+)
desktop/Set-IncognitoBlock.ps1, set-incognito-block.sh
ios/                     .mobileconfig generator
install-via-usb.ps1      sideload helper
docs/                    Samsung setup, install troubleshooting, raw research
```

There is no automated test suite. Android changes are verified on an emulator or
the real device; policy changes are verified by reading the browser's own policy
page (`chrome://policy`, `vivaldi://policy`, `whale://policy`). Adult-site
blocking is verified by resolving `www.google.com` on the device
(`adb shell ping -c 1 www.google.com`): through any of the three filters it comes
back as `216.239.38.120`, Google's forced-SafeSearch address, instead of a normal
Google IP.

Building on Windows: the project path contains Korean and `gradlew` cannot start
there (`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`).
Run the extracted distribution's `gradle.bat` from
`%USERPROFILE%\.gradle\wrapper\dists\gradle-9.1.0-all\*\gradle-9.1.0\bin` with
`JAVA_HOME` at Android Studio's `jbr`. Output lands in `~/.incognito-build/`.

## Facts that cost real research — do not re-derive them

- **Some browsers have no policy key at all**: Opera and Opera GX (no enterprise
  policy), Samsung Internet (Knox `BrowserPolicy` has no private-mode entry) and
  Firefox for Android (no managed configuration). For those, only app hiding or
  launch blocking works — sending `DisablePrivateBrowsing` to Android Firefox is
  dead code.
- Firefox desktop: set **both** `PrivateBrowsingModeAvailability` (FF130+ /
  ESR128.2+) and the legacy `DisablePrivateBrowsing`.
- `IncognitoModeUrlAllowlist` (Chrome 147+) and `InPrivateModeUrlAllowlist`
  **override** the blocking policy. One left behind is a hole.
- A guest profile is a profile where every window is incognito, by Chromium's own
  description. Without `BrowserGuestModeEnabled=0` and `BrowserAddPersonEnabled=0`
  (`BrowserAddProfileEnabled` on Edge), blocking incognito means nothing.
- Vivaldi and Naver Whale publish no vendor registry path — measure it on the
  machine instead of guessing.
- iOS: setting `webContent.blockedByFilter` to anything but `.none` disables
  Safari private browsing, which Apple documents. `com.apple.webcontent-filter`
  with only `ContentFilterUUID` installs by hand on an unsupervised iPhone from
  iOS 16. `allowSafariPrivateBrowsing` is iOS 26+ supervised devices only.
- Android detection: Chrome shows a "New Incognito tab" entry in normal mode too,
  so **word matching produces false positives and the weighted score is required.**
  View IDs and strings shift per browser version and locale, which is why the app
  carries a diagnostic mode that dumps what the device actually reports.
- Samsung **Secure Folder** is invisible to accessibility services and is the
  largest bypass. It has to be removed, not detected.
- `DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost` checks the
  resolver with a TLS socket opened **in the calling app's process**
  (`PrivateDnsConnectivityChecker`). Without `INTERNET` that socket fails with
  `EPERM`, so the call always returns `HOST_NOT_SERVING`. The device-owner tier
  therefore writes `private_dns_mode` / `private_dns_specifier` through the
  adb-granted `WRITE_SECURE_SETTINGS` and uses DPM only for the lock.
- `DISALLOW_CONFIG_PRIVATE_DNS` refuses every non-system write to those two
  keys — this app's and `adb shell settings put` alike — silently: exit 0,
  `putString` returns false, value unchanged. Lift it around the app's own
  writes and put it straight back (`SafeDns.unlocked`).
- Both Private DNS keys are readable from a targetSdk 36 app on Android 17
  (API 37). `LinkProperties.privateDnsServerName` is the fallback if a build
  ever refuses the read.

## Opened outside the workspace

Normally this repository sits inside the `AI Agent` workspace and inherits its
`CLAUDE.md` plus the memory server. A cloud session clones this repository alone
and has neither, so this file is all of it. Anywhere: verify a visible change in
the real UI, fix the README in the same commit as the behaviour it describes, and
write bulk output to a file rather than into the conversation.
