#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Start the daemon at boot, when daemon mode is on: the app's Settings switch
# writes 1 to the marker, as in Droidspaces (by hand: echo 1 > the marker). It
# daemonizes itself, refuses a second copy, and creates
# /data/local/USB-Arsenal/Run with the socket in it.

[ "$(cat /data/local/USB-Arsenal/.daemon_mode 2>/dev/null)" = 1 ] || exit 0
/data/local/USB-Arsenal/bin/usb-arsenal daemon
