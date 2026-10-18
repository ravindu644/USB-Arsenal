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

# The workspace keeps its own copy, as Droidspaces does in its bin/
mkdir -p /data/local/USB-Arsenal/bin
cp -f "$MODPATH/system/bin/usb-arsenal" /data/local/USB-Arsenal/bin/usb-arsenal
chmod 0755 /data/local/USB-Arsenal/bin/usb-arsenal

ui_print "- USB Arsenal for $ARCH installed"
ui_print "- The daemon starts on boot; containers reach it by bind-mounting"
ui_print "  /data/local/USB-Arsenal/Run to /var/lib/USB-Arsenal/Run"
