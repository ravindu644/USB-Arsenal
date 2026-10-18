# Troubleshooting

Problems met while testing, and ones that follow from known kernel limits.

**ADB disconnected the moment a command ran.**
Expected. Taking the USB port from Android replaces its ADB and MTP device.
`usb-arsenal stop` brings them back. To run commands over ADB anyway, wrap them in a
`nohup sh -c "..." &` so they finish after ADB drops, or use a root terminal on the phone
or SSH into a container.

**The host sees the disk, but every read fails ("unrecovered read error").**
The kernel cannot read the image's SELinux label. This happens to files in a container's
filesystem, `/tmp`, or `/data/local/tmp`. Current versions relabel the image
automatically and print "Relabelled ...". If the relabel itself fails, move the image to
`/sdcard` or `/data`.

**A Windows ISO boots, then setup fails part-way.**
CD-ROM mode stops at about 2.36 GB, and the kernel silently cuts the rest off; USB
Arsenal warns about it. Use `--mode dvd` (auto mode picks it for big plain ISOs); see
[usage.md](usage.md#storage).

**WinPE boots, but its programs or `sources` folder are missing.**
The ISO was served with `--mode disk`. Its 512-byte sectors put the UDF anchor where
Windows does not look, so Windows never mounts the volume. Use `--mode dvd`.

**`dvd` mode refuses: "SELinux does not let the kernel read loop devices".**
Install the module (it loads the rule at boot) or load the rule the message prints.

**Swapping an image made the device re-appear on the host.**
That happens when switching between disk and CD-ROM, or when the host had locked the
drive (Linux does that to a drive it has open). It is a re-plug by design; the other
functions come back with it.

**Tethering is up but the host has no address.**
The host's network manager may not have picked up the adapter, especially if its name
changed because storage or HID were added. On Linux: `nmcli device connect <adapter>`.
See [tethering.md](tethering.md#the-host-side).

**The computer lost its own internet while tethering.**
The host routed everything through the phone, because the wired link won over Wi-Fi.
When the phone's side then changes (an OpenWrt restart, a `stop`), the computer stalls.
Give the link a "never default route" profile; see
[tethering.md](tethering.md#the-host-side).

**`check` marks NCM as unsafe on a Samsung phone.**
The kernel has Samsung's f_ncm bug: a second NCM instance panics it. USB Arsenal tethers
over RNDIS there. Patch `0003` in `kernel-patches/4.14/` fixes the kernel, and NCM comes
back by itself. See [kernel-requirements.md](kernel-requirements.md).

**`check` says "piggyback backend".**
A stock Samsung kernel that cannot build a second gadget. Everything works, but Android
rewrites its gadget on a replug or a USB mode change, which drops USB Arsenal's
functions. Patches `0001` and `0002` fix that.

**"Permission denied" talking to the daemon.**
Only root and the `usb-arsenal` group (gid 3460) may use it. `usb-arsenal version` says
"no access" in that case. See [containers.md](containers.md#who-may-use-it).

**"More than one USB device controller".**
Pick one with `--udc NAME`; `check` lists them.

**A command was killed, or the phone lost power mid-way.**
Run `usb-arsenal stop`. It reads the hand-back record in the workspace and finishes
restoring the port. A record from before a reboot is ignored, since the reboot already
restored it.

**Profile changes do not show on the host.**
`identity set` re-plugs the gadget only if it is live; otherwise the change applies at the
next start. If the active profile is a copy (made from a container path the daemon cannot
see), run `identity set` again after editing the original. `identity show` says which it
is.
