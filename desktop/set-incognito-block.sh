#!/usr/bin/env bash
# IncognitoBlocker - macOS / Linux 브라우저 프라이빗 모드 차단
#
#   사용법:
#     sudo ./set-incognito-block.sh apply
#     sudo ./set-incognito-block.sh remove
#     ./set-incognito-block.sh status
#
# 반드시 root 로 실행해야 합니다. 사용자 권한으로 쓴 설정은
# 그 사용자가 5초 만에 되돌릴 수 있어 차단이 되지 않습니다.

set -euo pipefail

ACTION="${1:-status}"
OS="$(uname -s)"

need_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "  [X] root 권한이 필요합니다:  sudo $0 $ACTION" >&2
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# Linux : /etc/<vendor>/policies/managed/*.json
#
#   경로는 Chromium 소스의 policy_paths.cc 에 정의된 값입니다.
#     Google Chrome  -> /etc/opt/chrome/policies
#     Chromium       -> /etc/chromium/policies
#     Microsoft Edge -> /etc/opt/edge/policies
#     Brave          -> /etc/brave/policies
#     Firefox        -> /etc/firefox/policies/policies.json
#
#   디렉터리는 반드시 root 소유여야 합니다. 일반 사용자가 쓸 수 있으면
#   그냥 덮어써서 무력화합니다 (Microsoft 도 문서에서 명시적으로 경고).
# ---------------------------------------------------------------------------

CHROMIUM_DIRS=(
    "/etc/opt/chrome/policies/managed"
    "/etc/chromium/policies/managed"
    "/etc/chromium-browser/policies/managed"
    "/etc/opt/edge/policies/managed"
    "/etc/brave/policies/managed"
    "/etc/opt/chrome_for_testing/policies/managed"
)

FIREFOX_FILES=(
    "/etc/firefox/policies/policies.json"
)

POLICY_FILE="incognito-blocker.json"

linux_apply() {
    for dir in "${CHROMIUM_DIRS[@]}"; do
        mkdir -p "$dir"
        chown root:root "$dir"
        chmod 755 "$dir"
        # Edge 는 키 이름이 다르다. 두 키를 모두 써도 무시될 뿐 해가 없다.
        cat > "$dir/$POLICY_FILE" <<'JSON'
{
  "IncognitoModeAvailability": 1,
  "InPrivateModeAvailability": 1,
  "BrowserGuestModeEnabled": false,
  "BrowserAddPersonEnabled": false,
  "BrowserAddProfileEnabled": false,
  "TorDisabled": true
}
JSON
        chown root:root "$dir/$POLICY_FILE"
        chmod 644 "$dir/$POLICY_FILE"
        echo "  적용: $dir/$POLICY_FILE"
    done

    for f in "${FIREFOX_FILES[@]}"; do
        mkdir -p "$(dirname "$f")"
        cat > "$f" <<'JSON'
{
  "policies": {
    "PrivateBrowsingModeAvailability": 1,
    "DisablePrivateBrowsing": true,
    "DisableProfileImport": true,
    "DisableProfileRefresh": true
  }
}
JSON
        chown root:root "$f"
        chmod 644 "$f"
        echo "  적용: $f"
    done
}

linux_remove() {
    for dir in "${CHROMIUM_DIRS[@]}"; do
        rm -f "$dir/$POLICY_FILE" && echo "  제거: $dir/$POLICY_FILE" || true
    done
    for f in "${FIREFOX_FILES[@]}"; do
        rm -f "$f" && echo "  제거: $f" || true
    done
}

linux_status() {
    for dir in "${CHROMIUM_DIRS[@]}"; do
        if [ -f "$dir/$POLICY_FILE" ]; then echo "  [O] $dir/$POLICY_FILE"; else echo "  [ ] $dir"; fi
    done
    for f in "${FIREFOX_FILES[@]}"; do
        if [ -f "$f" ]; then echo "  [O] $f"; else echo "  [ ] $f"; fi
    done
}

# ---------------------------------------------------------------------------
# macOS : /Library/Managed Preferences/<domain>.plist  (root 소유)
#
#   사용자 도메인에 defaults write 로 쓰면 사용자가 defaults delete 로 지웁니다.
#   반드시 /Library/Managed Preferences 아래에 root 로 써야 정책이 됩니다.
#
#   Safari 는 여기에 해당 키가 없습니다.
#   macOS 26 미만에서 Safari 프라이빗 브라우징을 끄는 MDM 키는 존재하지 않고,
#   스크린 타임 > 콘텐츠 및 개인 정보 보호 제한 > 웹 콘텐츠 로만 막을 수 있습니다.
# ---------------------------------------------------------------------------

MAC_DOMAINS=(
    "com.google.Chrome:IncognitoModeAvailability"
    "com.microsoft.Edge:InPrivateModeAvailability"
    "com.brave.Browser:IncognitoModeAvailability"
    "org.chromium.Chromium:IncognitoModeAvailability"
)

MANAGED="/Library/Managed Preferences"

mac_apply() {
    mkdir -p "$MANAGED"
    for entry in "${MAC_DOMAINS[@]}"; do
        domain="${entry%%:*}"
        key="${entry##*:}"
        plist="$MANAGED/$domain.plist"
        /usr/libexec/PlistBuddy -c "Add :$key integer 1" "$plist" 2>/dev/null \
            || /usr/libexec/PlistBuddy -c "Set :$key 1" "$plist"
        /usr/libexec/PlistBuddy -c "Add :BrowserGuestModeEnabled bool false" "$plist" 2>/dev/null \
            || /usr/libexec/PlistBuddy -c "Set :BrowserGuestModeEnabled false" "$plist"
        chown root:wheel "$plist"
        chmod 644 "$plist"
        echo "  적용: $plist ($key = 1)"
    done

    # Firefox
    ff="$MANAGED/org.mozilla.firefox.plist"
    /usr/libexec/PlistBuddy -c "Add :EnterprisePoliciesEnabled bool true" "$ff" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Add :PrivateBrowsingModeAvailability integer 1" "$ff" 2>/dev/null \
        || /usr/libexec/PlistBuddy -c "Set :PrivateBrowsingModeAvailability 1" "$ff"
    /usr/libexec/PlistBuddy -c "Add :DisablePrivateBrowsing bool true" "$ff" 2>/dev/null || true
    chown root:wheel "$ff"; chmod 644 "$ff"
    echo "  적용: $ff"

    killall cfprefsd 2>/dev/null || true
    echo ""
    echo "  Safari 는 이 방법으로 막을 수 없습니다."
    echo "  시스템 설정 > 스크린 타임 > 콘텐츠 및 개인 정보 보호 제한 > 웹 콘텐츠"
    echo "  에서 '성인 웹사이트 제한' 을 켜고, 스크린 타임 암호를 설정하세요."
}

mac_remove() {
    for entry in "${MAC_DOMAINS[@]}"; do
        domain="${entry%%:*}"
        rm -f "$MANAGED/$domain.plist" && echo "  제거: $MANAGED/$domain.plist" || true
    done
    rm -f "$MANAGED/org.mozilla.firefox.plist" || true
    killall cfprefsd 2>/dev/null || true
}

mac_status() {
    for entry in "${MAC_DOMAINS[@]}"; do
        domain="${entry%%:*}"
        key="${entry##*:}"
        plist="$MANAGED/$domain.plist"
        if [ -f "$plist" ]; then
            val="$(/usr/libexec/PlistBuddy -c "Print :$key" "$plist" 2>/dev/null || echo '-')"
            echo "  [O] $domain  $key=$val"
        else
            echo "  [ ] $domain"
        fi
    done
}

# ---------------------------------------------------------------------------

echo ""
echo "  IncognitoBlocker - $OS"
echo "  --------------------------------"

case "$ACTION" in
    apply)
        need_root
        if [ "$OS" = "Darwin" ]; then mac_apply; else linux_apply; fi
        echo ""
        echo "  브라우저를 완전히 종료했다가 다시 켜야 적용됩니다."
        echo "  확인: chrome://policy  edge://policy  about:policies"
        echo ""
        echo "  주의: 차단 대상 사용자가 sudo 를 쓸 수 있으면 이 설정은 무의미합니다."
        ;;
    remove)
        need_root
        if [ "$OS" = "Darwin" ]; then mac_remove; else linux_remove; fi
        ;;
    status)
        if [ "$OS" = "Darwin" ]; then mac_status; else linux_status; fi
        ;;
    *)
        echo "  사용법: $0 {apply|remove|status}" >&2
        exit 1
        ;;
esac
echo ""
