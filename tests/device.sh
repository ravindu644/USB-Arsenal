#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# On-device test of USB Arsenal: every backend command the app sends, then the app itself,
# driven by the text its screens show. It needs the app and its backend installed, and the
# phone plugged into a computer. adbd stops while the gadget holds the port, so it runs as
# root in the background:
#
#   adb push tests/device.sh /data/local/tmp/
#   adb shell "su -c 'nohup sh /data/local/tmp/device.sh [CONTAINER [IFACE]] >/dev/null 2>&1 &'"
#   adb shell cat /data/local/tmp/uatest/log    # once it ends in DONE
#
# CONTAINER names a running Droidspaces container to test the tether gateway with (IFACE:
# the cable's name inside it, default usb0); without one the gateway steps are skipped.
# Screenshots land beside the log. The setup, the animation scale and the screen timeout
# are put back at the end, and the port is handed back.

UA=/data/local/USB-Arsenal/bin/usb-arsenal
GS=/data/local/USB-Arsenal/Gadget
APP=com.usbarsenal.app
D=/data/local/tmp/uatest
GW=${1:-}
GWIF=${2:-usb0}
rm -rf $D; mkdir -p $D
# Blank images of our own, so nothing of the user's is ever served
IMG=$D
dd if=/dev/zero of=$IMG/cd.img bs=1048576 count=8 2>/dev/null
dd if=/dev/zero of=$IMG/disk.img bs=1048576 count=8 2>/dev/null
exec >$D/log 2>&1

pass=0; fail=0; failed=""
ok() { pass=$((pass + 1)); echo "  PASS $*"; }
no() { fail=$((fail + 1)); failed="$failed
  $*"; echo "  FAIL $*"; }
check() { # NAME COMMAND...: pass when the command succeeds
  n=$1; shift
  if "$@" >/dev/null 2>&1; then ok "$n"; else no "$n"; fi
}
section() { echo; echo "== $*"; }

# The kernel complains before it panics: any new warning fails the step that caused it
KPAT='WARNING:|BUG:|Oops|Call trace|Unable to handle|panic'
kbase=$(dmesg | grep -cE "$KPAT")
kernel() {
  now=$(dmesg | grep -cE "$KPAT")
  if [ "$now" -gt "$kbase" ]; then
    no "dmesg after $1: $(dmesg | grep -E "$KPAT" | tail -3)"
    kbase=$now
  fi
}

st() { $UA status --json 2>/dev/null; }
has() { st | grep -q "$1"; }
wait_for() { # PATTERN SECONDS: until status --json has it
  i=0
  while [ $i -lt "$2" ]; do has "$1" && return 0; sleep 1; i=$((i + 1)); done
  return 1
}
setupval() { grep "^$1=" $GS/setup.conf | sed 's/ *#.*//; s/^[^=]*=//; s/ *$//'; }
# What runs; with nothing run since boot there is no file, and revert falls back to the
# default, which is 0 for the only key read here
runval() {
  [ -f $GS/running.conf ] || { echo 0; return; }
  grep "^$1=" $GS/running.conf | sed 's/ *#.*//; s/^[^=]*=//; s/ *$//'
}

# UI: uiautomator's tree, one node a line; a control is found by its text or content-desc
dump() {
  i=0
  while [ $i -lt 4 ]; do
    uiautomator dump $D/raw.xml >/dev/null 2>&1 && break
    sleep 1; i=$((i + 1))
  done
  sed 's/<node /\n<node /g' $D/raw.xml >$D/ui.txt
}
center() { # TEXT [N]: the Nth node with exactly that text or description
  grep -F -e "text=\"$1\"" -e "content-desc=\"$1\"" $D/ui.txt | sed -n "${2:-1}p" |
    sed 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/' |
    awk 'NF==4{print int(($1+$3)/2), int(($2+$4)/2)}'
}
focused() { dumpsys window | grep mCurrentFocus | grep -q "$APP"; }
tap() { # TEXT [N]
  focused || { no "tap '$1': the app is not in front"; return 1; }
  dump
  xy=$(center "$1" "$2")
  [ -n "$xy" ] || { no "tap '$1': not on screen"; return 1; }
  input tap $xy
}
back() { focused && input keyevent KEYCODE_BACK; }
sees() { dump; grep -qiF "$1" $D/ui.txt; }
shot() { screencap -p $D/$1.png; }
screen() { # NAME OPEN-TEXT [N] EXPECT: opens a screen from Home, checks it, comes back
  tap "$2" "$3" && sleep 2
  if sees "$4"; then ok "$1 opens"; else no "$1 opens (no '$4')"; fi
  shot "$1"
  back; sleep 1.5
  if sees "SETTINGS"; then ok "$1 Back returns Home"; else no "$1 Back returns Home"; fi
}

cleanup() {
  section "cleanup"
  $UA stop
  if [ -f $D/setup.conf.bak ]; then cp $D/setup.conf.bak $GS/setup.conf; else rm -f $GS/setup.conf; fi
  settings put global animator_duration_scale "$anim"
  settings put system screen_off_timeout "$tmo"
  echo
  echo "RESULT: $pass passed, $fail failed$failed"
  echo DONE
}

section "preflight"
[ "$(id -u)" = 0 ] || { echo "needs root"; echo DONE; exit 1; }
$UA version
if has '"bound":1'; then echo "the gadget is bound; stop it first"; echo DONE; exit 1; fi
[ -f $GS/setup.conf ] && cp $GS/setup.conf $D/setup.conf.bak
anim=$(settings get global animator_duration_scale)
tmo=$(settings get system screen_off_timeout)
trap cleanup EXIT
# Still screens: uiautomator waits for an idle frame, and the app rests its loops without motion
settings put global animator_duration_scale 0
settings put system screen_off_timeout 600000
input keyevent KEYCODE_WAKEUP
gw=0
if [ -n "$GW" ]; then
  if /data/local/Droidspaces/bin/droidspaces show --json 2>/dev/null | grep -qF "\"name\":\"$GW\""; then gw=1; else no "container $GW is not running"; fi
fi

section "backend: reads"
check "check --json" sh -c "$UA check --json | grep -q '{'"
check "status --json" sh -c "$UA status --json | grep -q '\"pending\"'"
check "identity show --json" sh -c "$UA identity show --json | grep -q '{'"
check "setup show" $UA setup show

section "backend: setup"
$UA setup set hid_interval=4
check "setup set" test "$(setupval hid_interval)" = 4
$UA setup revert hid
check "setup revert" test "$(setupval hid_interval)" = "$(runval hid_interval)"

section "backend: storage"
$UA setup set storage_lun0=cdrom:$IMG/cd.img storage_lun1= hid_interval=0
check "storage start" $UA storage start
check "storage lun0 served" has '"lun":0'
check "bound" has '"bound":1'
kernel "storage start"
$UA setup set storage_lun1=disk:$IMG/disk.img
check "storage pending after setup set" has '"storage":1'
check "storage apply" $UA storage apply
check "storage lun1 served" has '"lun":1'
check "storage not pending" has '"storage":0'
check "storage eject --lun 1" $UA storage eject --lun 1
kernel "storage apply"

section "backend: hid"
check "hid start" $UA hid start
check "hid nodes" sh -c 'ls /dev/hidg*'
kb=$(st | grep -o '{[^}]*"keyboard"[^}]*}' | grep -o '"node":"[^"]*"' | sed 's/"node":"//; s/"//')
[ -n "$kb" ] && check "hid write (empty report)" sh -c "printf '\0\0\0\0\0\0\0\0' | $UA hid write $kb 8"
$UA setup set hid_interval=2
check "hid pending" has '"hid":1'
check "hid apply" $UA hid apply
check "hid running interval 2" test "$(runval hid_interval)" = 2
check "storage kept through hid apply" has '"lun":0'
kernel "hid"

section "backend: tether"
if [ $gw = 1 ]; then
  $UA setup set "tether_gateway=$GW" "tether_gateway_iface=$GWIF" tether_net=1
  check "tether start (gateway)" $UA tether start
  check "gateway up" has '"gateway":1'
  check "network side runs" has '"running":1'
  check "tether stop" $UA tether stop
  kernel "tether gateway"
  # A gateway wins over tether_net=0 (the bug this session left on the phone)
  $UA setup set tether_net=0
  $UA tether start
  check "gateway wins over tether_net=0" has '"gateway":1'
  $UA tether stop
fi
$UA setup set tether_gateway= tether_net=0
check "tether start (no network side)" $UA tether start
check "tether function up" has '"iface":"usb'
check "tether stop" $UA tether stop
kernel "tether"

section "backend: stop"
check "stop" $UA stop
check "handed back" wait_for '"bound":0' 10
kernel "stop"

section "app: screens"
am force-stop $APP
monkey -p $APP -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
check "app in front" focused
if sees "SETTINGS"; then ok "Home"; else no "Home"; fi
shot home
screen storage Storage 1 MODE
screen tether Tether 1 CONFIGURE
screen hid HID 1 INTERVAL
screen log LOG 1 "COPY LOGS"
tap SETTINGS && sleep 2
if sees BACKEND; then ok "settings opens"; else no "settings opens"; fi
shot settings
tap PROFILE && sleep 2
if sees PROFILES; then ok "identity opens"; else no "identity opens"; fi
shot identity
back; sleep 1.5; back; sleep 1.5
if sees "SETTINGS"; then ok "Identity and Settings Back to Home"; else no "Identity and Settings Back to Home"; fi

section "app: HID switch, Not applied sheet"
$UA setup set hid_interval=1
sleep 3
tap HID 2
if wait_for '"function":"keyboard"' 20; then ok "HID switch starts hid"; else no "HID switch starts hid"; fi
sleep 3
kernel "app hid start"
# The bug: Back a beat after an edit, once it was sent and before a status read, left unasked
tap HID 1 && sleep 2
tap "Longer interval"; sleep 1
back; sleep 1
if sees "not applied"; then ok "Back 1s after an edit asks"; else no "Back 1s after an edit asks"; fi
shot hid-sheet-fast
tap DISCARD; sleep 4
check "Discard leaves nothing pending" has '"hid":0'
check "Discard puts the setup back" test "$(setupval hid_interval)" = "$(runval hid_interval)"
if sees "SETTINGS"; then ok "Discard leaves for Home"; else no "Discard leaves for Home"; fi
# Slow Back, then Apply
tap HID 1 && sleep 2
tap "Longer interval"; sleep 4
back; sleep 1
if sees "not applied"; then ok "Back 4s after an edit asks"; else no "Back 4s after an edit asks"; fi
tap APPLY
if wait_for '"hid":0' 20; then ok "Apply leaves nothing pending"; else no "Apply leaves nothing pending"; fi
check "Apply runs the new interval" test "$(runval hid_interval)" = 2
kernel "app hid apply"
sleep 3
tap HID 1 && sleep 2
back; sleep 1
if sees "not applied"; then no "Back with no edit asks"; else ok "Back with no edit leaves"; fi

section "app: storage"
tap Storage 2
if wait_for '"lun":0' 20; then ok "Storage switch starts storage"; else no "Storage switch starts storage"; fi
sleep 3
tap Storage 1 && sleep 2
tap DVD; sleep 1
back; sleep 1
if sees "not applied"; then ok "Storage: Back after a mode change asks"; else no "Storage: Back after a mode change asks"; fi
shot storage-sheet
tap DISCARD; sleep 4
check "Storage: nothing pending" has '"storage":0'
kernel "app storage"

if [ $gw = 1 ]; then
  section "app: tether gateway"
  $UA setup set "tether_gateway=$GW" "tether_gateway_iface=$GWIF" tether_net=1
  sleep 3
  tap Tether 2
  if wait_for '"gateway":1' 25; then ok "Tether switch starts the gateway"; else no "Tether switch starts the gateway"; fi
  sleep 3
  tap Tether 1 && sleep 2
  if sees "virtual cable"; then ok "Tether chain shows the gateway"; else no "Tether chain shows the gateway"; fi
  shot tether-gateway
  tap RNDIS; sleep 1
  back; sleep 1
  if sees "not applied"; then ok "Tether: Back after an edit asks"; else no "Tether: Back after an edit asks"; fi
  tap DISCARD; sleep 4
  check "Tether: nothing pending" has '"tether":0'
  kernel "app tether"
fi

section "app: log, stop all"
tap LOG && sleep 2
if sees "# usb-arsenal"; then ok "Log shows the commands"; else no "Log shows the commands"; fi
shot log-after
back; sleep 1.5
tap "STOP ALL"
if wait_for '"bound":0' 20; then ok "Stop all hands back"; else no "Stop all hands back"; fi
sleep 3
if sees "PORT WITH ANDROID"; then ok "Home says the port is Android's"; else no "Home says the port is Android's"; fi
shot home-after
kernel "stop all"
