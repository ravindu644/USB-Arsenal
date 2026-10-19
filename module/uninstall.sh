#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Give the controller back to Android before the binary goes away.

MODDIR=${0%/*}
UA="$MODDIR/system/bin/usb-arsenal"
UA_NO_PROXY=1 "$UA" stop
"$UA" daemon stop
# Only the link goes; the rest of the workspace (profiles, logs) is the user's
[ -L /data/local/USB-Arsenal/bin/usb-arsenal ] && rm -f /data/local/USB-Arsenal/bin/usb-arsenal
