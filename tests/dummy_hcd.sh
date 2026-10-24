#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# End-to-end check of the gadget core on a Linux PC, no phone needed.
# dummy_hcd gives this machine a virtual device controller whose host side is
# this same machine, so every gadget we bind shows up here as a USB device.
#
#   sudo tests/dummy_hcd.sh [path/to/usb-arsenal]
#
# Leaves nothing behind on success: the last step is `stop`, and the script
# checks that it really removed the gadget.

set -eu
B=${1:-output/usb-arsenal}
UDC=dummy_udc.0
G=/sys/kernel/config/usb_gadget/usb_arsenal
TMP=$(mktemp -d)
pass=0

ok() { pass=$((pass + 1)); echo "ok $pass - $1"; }
die() { echo "FAIL: $1"; "$B" stop >/dev/null 2>&1 || true; exit 1; }
trap 'rm -rf "$TMP"' EXIT

[ "$(id -u)" = 0 ] || die "run as root"
modprobe dummy_hcd
modprobe libcomposite
[ -e /sys/class/udc/$UDC ] || die "dummy_hcd gave no $UDC"
# A dummy controller is never picked by itself, so it is always named
A="--udc $UDC"

"$B" $A check >/dev/null || die "check reports missing MUST HAVE features"
ok "check passes"

# A 2 MB "disk": an MBR signature is all auto mode looks at
truncate -s 2M "$TMP/disk.img"
printf '\125\252' | dd of="$TMP/disk.img" bs=1 seek=510 conv=notrunc 2>/dev/null
"$B" $A storage start "$TMP/disk.img" || die "storage start"
[ "$(cat /sys/class/udc/$UDC/state)" = configured ] || die "host did not configure the gadget"
grep -q 1 $G/functions/mass_storage.ua/lun.0/ro || die "auto mode should pick a read-only disk"
grep -q 0 $G/functions/mass_storage.ua/lun.0/cdrom || die "MBR image must not be a CD-ROM"
ok "storage start: bound, configured, disk mode"

# No MBR: auto mode must fall back to CD-ROM. A mode change rebinds, by design.
truncate -s 2M "$TMP/plain.iso"
"$B" $A storage start "$TMP/plain.iso" || die "storage hot-swap"
grep -q 1 $G/functions/mass_storage.ua/lun.0/cdrom || die "plain image should be a CD-ROM"
grep -q "$TMP/plain.iso" $G/functions/mass_storage.ua/lun.0/file || die "swap did not load the new image"
ok "storage switch to CD-ROM mode"

# Same mode: a medium change in place (or a rebind if the host locked it)
cp "$TMP/plain.iso" "$TMP/plain2.iso"
"$B" $A storage start "$TMP/plain2.iso" || die "same-mode swap"
grep -q "$TMP/plain2.iso" $G/functions/mass_storage.ua/lun.0/file || die "same-mode swap did not load"
[ "$(cat /sys/class/udc/$UDC/state)" = configured ] || die "gadget not configured after swap"
ok "storage same-mode swap"

"$B" $A hid start || die "hid start"
[ -L $G/configs/c.1/hid.ua_kbd ] && [ -L $G/configs/c.1/hid.ua_mouse ] || die "hid functions not linked"
grep -q "$TMP/plain2.iso" $G/functions/mass_storage.ua/lun.0/file || die "storage lost its image across the rebind"
ok "hid start alongside storage"

"$B" $A tether start --no-net || die "tether start --no-net"
[ -L $G/configs/c.1/ncm.ua ] || die "ncm not linked"
ok "tether --no-net adds NCM"

# The setup: a start saves its arguments, `setup set` edits them, `apply`
# brings the kernel to them LUN by LUN, and status says when they differ
"$B" $A setup show | grep -q "storage_lun0 *auto:$TMP/plain2.iso" || die "start did not save its image as the setup"
"$B" $A status --json | grep -q '"pending":{"storage":0,"tether":0,"hid":0}' || die "nothing should be pending after a start"
truncate -s 2M "$TMP/second.img"
"$B" $A setup set storage_lun1=rw:$TMP/second.img || die "setup set"
"$B" $A setup set storage_lun1=bogus:$TMP/second.img 2>/dev/null && die "setup set must refuse a bad mode"
"$B" $A status --json | grep -q '"pending":{"storage":1,' || die "a changed setup should read as pending"
"$B" $A storage apply || die "storage apply"
grep -q "$TMP/second.img" $G/functions/mass_storage.ua/lun.1/file || die "apply did not add LUN 1"
grep -q "$TMP/plain2.iso" $G/functions/mass_storage.ua/lun.0/file || die "apply touched LUN 0"
"$B" $A storage apply | grep -q "already runs" || die "a second apply should change nothing"
"$B" $A setup set storage_lun1= || die "setup set (clear)"
"$B" $A storage apply || die "storage apply (eject)"
[ -z "$(cat $G/functions/mass_storage.ua/lun.1/file)" ] || die "apply did not eject LUN 1"
"$B" $A setup set hid_interval=3 || die "setup set hid_interval"
"$B" $A status --json | grep -q '"hid":1}' || die "a changed interval should read as pending"
"$B" $A hid apply || die "hid apply"
[ "$(cat /sys/class/udc/$UDC/state)" = configured ] || die "gadget not configured after hid apply"
"$B" $A status --json | grep -q '"hid":0}' || die "hid apply should clear pending"
"$B" $A setup set hid_interval=5 && "$B" $A setup revert hid || die "setup revert"
"$B" $A setup show | grep -q "^hid_interval *3 " || die "revert did not take the running value"
ok "setup: saved by start, applied LUN by LUN, pending in status"

"$B" $A status | grep -q "LUN 0" || die "status does not show storage"
ok "status"

"$B" $A hid stop || die "hid stop"
[ ! -e $G/functions/hid.ua_kbd ] || die "hid stop left the function behind"
[ "$(cat /sys/class/udc/$UDC/state)" = configured ] || die "gadget did not come back after hid stop"
ok "hid stop rebinds the rest"

"$B" $A stop || die "stop"
[ ! -e $G ] || die "stop left $G behind"
[ -z "$(cat /sys/class/udc/$UDC/function 2>/dev/null)" ] || die "UDC still bound after stop"
"$B" $A stop >/dev/null || die "a second stop must be a no-op"
ok "stop tears down, and is idempotent"

# A bare start runs the setup the earlier starts saved; stop forgets what ran
"$B" $A setup show | grep -q "^storage_lun0 *auto:$TMP/plain2.iso *-" || die "stop should clear what runs, not the setup"
"$B" $A storage start || die "bare start from the setup"
grep -q "$TMP/plain2.iso" $G/functions/mass_storage.ua/lun.0/file || die "bare start did not load the setup"
"$B" $A stop || die "stop after the bare start"
"$B" $A setup reset || die "setup reset"
ok "a bare start runs the setup"

echo "all $pass checks passed"
