#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# KernelSU / Magisk install script: keep the one binary this device can run.

SKIPUNZIP=0

case "$ARCH" in
  arm64) BIN=usb-arsenal-aarch64 ;;
  arm) BIN=usb-arsenal-armhf ;;
  x64) BIN=usb-arsenal-x86_64 ;;
  x86) BIN=usb-arsenal-x86 ;;
  *) abort "! Unsupported architecture: $ARCH" ;;
esac
[ -f "$MODPATH/bin/$BIN" ] || abort "! $BIN is missing from the zip"

mkdir -p "$MODPATH/system/bin"
mv "$MODPATH/bin/$BIN" "$MODPATH/system/bin/usb-arsenal"
rm -rf "$MODPATH/bin"
set_perm "$MODPATH/system/bin/usb-arsenal" 0 0 0755

# The workspace's bin/ points at the module's binary, so there is one copy to
# update. The manager moves the module from modules_update/ to modules/ at
# the next boot, so link to where it will live, not to $MODPATH.
mkdir -p /data/local/USB-Arsenal/bin
rm -f /data/local/USB-Arsenal/bin/usb-arsenal
ln -s "/data/adb/modules/${MODPATH##*/}/system/bin/usb-arsenal" \
  /data/local/USB-Arsenal/bin/usb-arsenal

ui_print "- USB Arsenal for $ARCH installed"
ui_print "- The daemon starts on boot; containers reach it by bind-mounting"
ui_print "  /data/local/USB-Arsenal/Run to /var/lib/USB-Arsenal/Run"
