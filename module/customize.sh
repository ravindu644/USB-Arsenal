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

# The binary lives in the workspace, as the app installs it: copied beside the
# target and renamed over it, so a binary that is running is never written to
UA=/data/local/USB-Arsenal/bin/usb-arsenal
mkdir -p /data/local/USB-Arsenal/bin
cp "$MODPATH/bin/$BIN" "$UA.new" && chmod 755 "$UA.new" && mv -f "$UA.new" "$UA" ||
  abort "! Cannot install $UA"
rm -rf "$MODPATH/bin"

# A flashed zip has no app to switch the PATH link on, so it makes it: after
# the reboot `usb-arsenal` works from any root shell. The app's Settings
# toggle removes it or makes it again
mkdir -p "$MODPATH/system/bin"
ln -sf "$UA" "$MODPATH/system/bin/usb-arsenal"

# What an older version left: the daemon's marker and socket directory
rm -rf /data/local/USB-Arsenal/.daemon_mode /data/local/USB-Arsenal/Run /data/local/USB-Arsenal/usb-arsenald.pid

ui_print "- USB Arsenal for $ARCH installed"
