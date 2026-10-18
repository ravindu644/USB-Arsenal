#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Give the controller back to Android before the binary goes away.

MODDIR=${0%/*}
UA="$MODDIR/system/bin/usb-arsenal"
UA_NO_PROXY=1 "$UA" stop
"$UA" daemon stop
