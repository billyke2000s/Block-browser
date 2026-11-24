#!/usr/bin/env bash
# End-to-end check of "Download video" on an Android emulator, run by .github/workflows/build.yml (job video-check).
#
# Serves the test streams from make-test-streams.sh on a local site (stream-server.py, reached through adb reverse),
# opens a page for each kind of video, uses the real menu: ⋮ → Download video → pick video and quality, then waits
# for the file in Downloads and checks it with ffprobe (video and audio present, about 12 seconds long). Also checks
# encrypted and live streams are refused. Screenshots, UI dumps and logcat go to $OUT for diagnosis.
#
# Usage: video-check.sh APK
set -uo pipefail

APK="${1:?APK path}"
PKG="io.github.billyke2000s.blockbrowser"
HERE="$(cd "$(dirname "$0")" && pwd)"
PORT=8766
SITE="http://localhost:$PORT"
OUT="${OUT:-video-check-output}"
STREAMS="${STREAMS_DIR:-$PWD/video-test-streams}"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.md"; : > "$SUMMARY"
FAILED=0
fail(){ echo "::error::$*"; echo "- ❌ $*" >> "$SUMMARY"; FAILED=1; }
pass(){ echo "PASS: $*"; echo "- ✅ $*" >> "$SUMMARY"; }

# Every adb call gets a time limit, so a stuck emulator fails the check with diagnostics instead of hanging the job.
ADB_BIN="$(command -v adb)"
adb(){ timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@"; }
device_state(){ echo "  - device: $("$ADB_BIN" get-state 2>&1 | head -1); focus: $(adb shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -s ' ')" >> "$SUMMARY"; }

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "### Download video — Android API $API" >> "$SUMMARY"

[ -f "$STREAMS/dash/manifest.mpd" ] || "$HERE/make-test-streams.sh" "$STREAMS" || { fail "Couldn't make test streams"; exit 1; }
python3 "$HERE/stream-server.py" "$PORT" "$OUT/requests.json" "$STREAMS" 2> "$OUT/server.log" &
SERVER=$!
adb logcat -c
"$ADB_BIN" logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT=$!
trap 'kill $SERVER $LOGCAT 2>/dev/null' EXIT
sleep 1
adb reverse "tcp:$PORT" "tcp:$PORT" || { fail "adb reverse failed"; exit 1; }

adb install -r -g "$APK" > "$OUT/install.txt" 2>&1 || { fail "APK install failed: $(tail -2 "$OUT/install.txt")"; exit 1; }
[ "$API" -ge 33 ] && adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null
adb shell rm -f "/sdcard/Download/Clip-*" "/sdcard/Download/clip*" 2>/dev/null

# ---- helpers ----------------------------------------------------------------------------------------------------
dump(){ for _ in 1 2 3 4 5; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
shot(){ adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
# find NAME ATTR VALUE [exact|contains|icase] -> "x y" of the first matching node's centre
find_node(){ python3 - "$OUT/$1.xml" "$2" "$3" "${4:-exact}" <<'PY'
import re, sys, xml.etree.ElementTree as ET
path, attr, value, mode = sys.argv[1:5]
try:
    nodes = list(ET.parse(path).iter("node"))
except Exception:
    sys.exit(0)
for n in nodes:
    v = n.get(attr) or ""
    ok = v == value if mode == "exact" else (value in v if mode == "contains" else value.lower() in v.lower())
    if ok:
        l, t, r, b = map(int, re.findall(r"-?\d+", n.get("bounds")))
        if r > l and b > t:
            print((l + r) // 2, (t + b) // 2); break
PY
}
has(){ [ -n "$(find_node "$@")" ]; }
tap(){ local xy; xy=$(find_node "$@"); [ -z "$xy" ] && return 1; adb shell input tap $xy; }
screen_text(){ python3 - "$OUT/$1.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
items = []
try:
    for n in ET.parse(sys.argv[1]).iter("node"):
        label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
        if label and label not in items: items.append(label[:50])
except Exception as e:
    items.append("(no screen dump: %s)" % e)
print(" · ".join(items[:50]))
PY
}
diag(){
  device_state
  dump "diag-$1" >/dev/null 2>&1; shot "diag-$1"
  echo "  - on screen [$1]: $(screen_text "diag-$1")" >> "$SUMMARY"
  local log; log=$(adb logcat -d 2>/dev/null | grep -E "VideoDownloadFlow|StreamDownloadService|MediaFinder|UBlockOriginOffer|FATAL|AndroidRuntime" | tail -8 | tr '\n' '|' | head -c 1500)
  [ -n "$log" ] && echo "  - log: $log" >> "$SUMMARY"
}
# Dismisses whatever first-start prompt is showing (add-on install prompt, default browser, tips...).
dismiss_prompts(){
  for _ in 1 2 3 4; do
    dump prompt >/dev/null 2>&1 || return
    local hit=0
    for label in "Cancel" "Not now" "NOT NOW" "No thanks" "Dismiss" "Close" "Got it" "Skip" "Maybe later" "OK"; do
      if has prompt text "$label"; then tap prompt text "$label"; hit=1; sleep 1; break; fi
    done
    [ $hit = 1 ] || return
  done
}
open_url(){ adb shell am start -a android.intent.action.VIEW -d "'$1'" -n "$PKG/org.mozilla.fenix.IntentReceiverActivity" >/dev/null 2>&1; }
wait_request(){ for _ in $(seq 1 "${2:-30}"); do python3 -c "import json,sys; d=json.load(open('$OUT/requests.json')); sys.exit(0 if any(r['path'].endswith('$1') for r in d['requests']) else 1)" 2>/dev/null && return 0; sleep 1; done; return 1; }
open_menu(){ # opens ⋮ and scrolls until "Download video" shows; returns 1 if it never does
  local d="$1"
  for attempt in 1 2 3; do
    dump "$d-a"
    tap "$d-a" content-desc "Menu" || tap "$d-a" content-desc "More options" || tap "$d-a" content-desc "Main menu" || tap "$d-a" content-desc "menu" icase
    sleep 2
    for _ in 1 2 3 4 5; do
      dump "$d-b"
      has "$d-b" text "Download video" && return 0
      adb shell input swipe 540 1700 540 900 400; sleep 1
    done
    adb shell input keyevent KEYCODE_BACK; sleep 1
  done
  return 1
}
# Taps "Download video", then the first video and the best quality when asked. $1 = dump prefix, $2 = item to pick.
start_download(){
  local d="$1" want="$2"
  tap "$d-b" text "Download video" || return 1
  for _ in $(seq 1 20); do
    sleep 1; dump "$d-c"
    if has "$d-c" text "Choose a video"; then tap "$d-c" text "$want" contains || return 1; continue; fi
    if has "$d-c" text "Choose a quality"; then tap "$d-c" text "360p" contains || tap "$d-c" text "p ·" contains; continue; fi
    has "$d-c" text "Checking the videos" contains && continue
    return 0
  done
  return 0
}
check_file(){ # $1 = file name pattern in Downloads, $2 = what it is
  local found=""
  for _ in $(seq 1 120); do
    found=$(adb shell "ls /sdcard/Download/ 2>/dev/null" | tr -d '\r' | grep -E "$1" | head -1)
    [ -n "$found" ] && break; sleep 1
  done
  if [ -z "$found" ]; then fail "$2: nothing saved to Downloads within 2 minutes"; return 1; fi
  sleep 3
  adb pull "/sdcard/Download/$found" "$OUT/$found" >/dev/null 2>&1
  local info; info=$(ffprobe -v error -show_entries format=duration:stream=codec_type -of json "$OUT/$found" 2>&1)
  echo "$info" > "$OUT/$found.ffprobe.json"
  local verdict; verdict=$(python3 - "$OUT/$found.ffprobe.json" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception as e:
    print("unreadable: %s" % e); sys.exit()
types = sorted(s.get("codec_type") for s in d.get("streams", []))
dur = float(d.get("format", {}).get("duration", 0))
problems = []
if "video" not in types: problems.append("no video track")
if "audio" not in types: problems.append("no audio track")
if not 10.5 <= dur <= 13.5: problems.append("duration %.1f s instead of 12" % dur)
print("ok %.1f s, %s" % (dur, "+".join(types)) if not problems else ", ".join(problems))
PY
)
  case "$verdict" in ok*) pass "$2 → $found ($verdict)";; *) fail "$2 → $found: $verdict";; esac
}

# ---- first start ------------------------------------------------------------------------------------------------
# "performancetest" (honoured on emulators) turns off onboarding and other first-start interruptions.
adb shell am start -n "$PKG/org.mozilla.fenix.HomeActivity" --ez performancetest true >/dev/null 2>&1
sleep 20
shot first-start
dump first-start
echo "- first start:" >> "$SUMMARY"; device_state
if has first-start text "uBlock Origin" contains; then pass "Offers uBlock Origin on first start"; else echo "- ⚠️ No uBlock Origin prompt seen on first start (needs internet access to addons.mozilla.org): $(screen_text first-start)" >> "$SUMMARY"; fi
dismiss_prompts

# ---- downloads --------------------------------------------------------------------------------------------------
run_case(){ # $1 = stream name, $2 = request path that shows the player loaded it, $3 = label, $4 = file pattern, $5 = item
  local name="$1" title="Clip-$1"
  open_url "$SITE/watch?s=$name&t=$title"
  wait_request "$2" 40 || { fail "$3: page never loaded $2"; diag "$name-load"; return; }
  sleep 4; dismiss_prompts
  shot "$name-page"
  if ! open_menu "$name"; then fail "$3: Download video not found in the menu"; diag "$name-menu"; adb shell input keyevent KEYCODE_BACK; return; fi
  if ! start_download "$name" "$5"; then fail "$3: couldn't pick the video"; diag "$name-pick"; return; fi
  shot "$name-started"
  if [ "$name" = "file" ]; then
    # Plain files go through the browser's own download prompt.
    for _ in $(seq 1 10); do dump file-prompt; tap file-prompt text "Download" && break; sleep 1; done
  fi
  check_file "$4" "$3" || diag "$name-result"
}
run_case ts "/streams/ts/master.m3u8" "HLS, two qualities, MPEG-TS" "^Clip-ts.*\.mp4$" "Video stream"
run_case fmp4 "/streams/fmp4/master.m3u8" "HLS, fragmented MP4, separate audio" "^Clip-fmp4.*\.mp4$" "Video stream"
run_case dash "/streams/dash/manifest.mpd" "DASH, separate video and audio" "^Clip-dash.*\.mp4$" "Video stream"
run_case file "/streams/clip.mp4" "Plain video file in a <video> element" "^clip.*\.mp4$" "Video file"

# ---- refusals ---------------------------------------------------------------------------------------------------
refuse_case(){ # $1 = stream name, $2 = request path, $3 = label, $4 = expected text
  open_url "$SITE/watch?s=$1&t=Clip-$1"
  wait_request "$2" 40 || { fail "$3: page never loaded $2"; return; }
  sleep 4; dismiss_prompts
  open_menu "$1" || { fail "$3: Download video not found in the menu"; diag "$1-menu"; return; }
  tap "$1-b" text "Download video"
  for _ in $(seq 1 15); do
    sleep 1; dump "$1-c"
    if has "$1-c" text "$4" contains; then pass "$3 is refused (“$4”)"; adb shell input keyevent KEYCODE_BACK; return; fi
  done
  fail "$3 was not refused"; diag "$1-refuse"; adb shell input keyevent KEYCODE_BACK
}
refuse_case enc "/streams/enc/index.m3u8" "Encrypted HLS" "copy-protected"
refuse_case live "/streams/live/index.m3u8" "Live HLS" "live stream"

if grep -qE "FATAL EXCEPTION.*|Process: $PKG" "$OUT/logcat.txt" && grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  fail "The app crashed (see logcat.txt)"
fi
echo >> "$SUMMARY"
cat "$SUMMARY"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$SUMMARY" >> "$GITHUB_STEP_SUMMARY"
exit $FAILED
