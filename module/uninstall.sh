#!/system/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Give the controller back to Android before the binary goes away.

UA=/data/local/USB-Arsenal/bin/usb-arsenal
QUACKY=/data/local/USB-Arsenal/bin/quacky
"$UA" stop
# Only the backend binaries go; the rest of the workspace (profiles, logs) is the user's
rm -f "$UA" "$QUACKY"
