#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Start the daemon at boot. It daemonizes itself, refuses a second copy, and
# creates /data/local/USB-Arsenal/Run with the socket in it.

MODDIR=${0%/*}
"$MODDIR/system/bin/usb-arsenal" daemon
