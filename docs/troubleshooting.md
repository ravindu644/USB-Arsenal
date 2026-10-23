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

**A `dvd` drive does not appear in the PC's boot menu.**
Older firmware does not boot a USB disk with 2048-byte sectors and no MBR (a 2012 laptop
ignored it in both UEFI and legacy mode). Use `--mode cdrom` on a kernel with the DVD
patch (see [kernel-requirements.md](kernel-requirements.md)), or a newer PC.

**After USB Arsenal, Android shows no USB notification and USB tethering is greyed out.**
Versions before 2026-10-19 (evening) removed their gadget on `stop`, and Android's configfs then
destroys `android0` by mistake (see developer/architecture.md). ADB still works. Reboot
the phone once; current versions keep their gadget, unbound and empty, and never trigger
it. `ls /sys/class/android_usb` should show `android0`.

**"SELinux does not let the kernel read /dev/block/…".**
A block device served as is (a partition, a `dm-*` device) is read by the kernel under
the device node's label, which most Android policies keep from it, and no label works on
every phone, so USB Arsenal cannot relabel it the way it does an image file. It stops
before the host sees a dead disk. To serve it anyway, set SELinux permissive by hand
(`setenforce 0`) and start it again. Serving a
partition, mounted or not, is your call: a filesystem the phone has mounted read-write
changes under the host (it may refuse to mount, or worse, with `--mode rw`).

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

**"This is stock Samsung configfs, which cannot create a second gadget".**
A stock Samsung 4.x kernel: its configfs cannot build a gadget next to Android's (the
`mkdir` fails as ENOMEM, and nothing is changed). USB Arsenal does not run on it;
patches `0001` and `0002` in `kernel-patches/4.14/` fix the kernel. See [kernel-requirements.md](kernel-requirements.md).

**"Permission denied" talking to the daemon.**
Only root and the `usb-arsenal` group (gid 3460) may use it. `usb-arsenal version` says
"no access" in that case. See [containers.md](containers.md#who-may-use-it).

**"More than one USB device controller".**
Pick one with `--udc NAME`; `check` lists them. `dummy_udc.*`, which every GKI kernel builds in,
is never counted, so it does not cause this.

**"No peripheral-mode controller in /sys/class/udc now", or a controller "not in /sys/class/udc now".**
A dual-role port leaves `/sys/class/udc` while it is a USB host (dwc3 removes its gadget side
on the switch), for example after an OTG device. Unplug it; the controller comes back once the
port is a device again, at the latest when a computer is plugged in.

**A command was killed, or the phone lost power mid-way.**
Run `usb-arsenal stop`. It reads the hand-back record in the workspace and finishes
restoring the port. A record from before a reboot is ignored, since the reboot already
restored it.

**Profile changes do not show on the host.**
`identity set` re-plugs the gadget only if it is live; otherwise the change applies at the
next start. If the active profile is a copy (made from a container path the daemon cannot
see), run `identity set` again after editing the original. `identity show` says which it
is.
