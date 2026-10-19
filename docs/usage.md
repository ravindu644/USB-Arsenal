# Usage

```
usb-arsenal [--udc NAME] [-v] <command> [<action>] [options] [args]
```

Every command explains itself: `usb-arsenal <command> --help`, `usb-arsenal help
<command>` and a bare `usb-arsenal <command>` all print the same page with examples.
A command's options may come before or after its action.

| Global option | Meaning |
|---|---|
| `--udc NAME` | The USB device controller to use. Only needed when `/sys/class/udc` lists more than one. |
| `-v`, `--verbose` | Debug output |
| `-h`, `--help`, `-V`, `--version` | Help, version |

There is one gadget. Each command adds or removes its part of it, so storage, network
and HID can run together. Adding or removing a part re-plugs the gadget for a moment,
and the host sees it re-appear with the rest intact. Removing the last part hands the
USB port back to Android. Defaults for every option come from the active
[identity profile](identity.md); an option given on the command line wins.

## check, status, stop

```sh
usb-arsenal check     # what this kernel can do; MUST HAVE lines block every command
usb-arsenal status    # what is live: storage images, HID nodes, the tether link
usb-arsenal stop      # everything off, the port back to its owner
```

`check --json` prints the same probes as one JSON object for a program to read: each key
(`root`, `configfs`, `udc`, `gadget`, `mass_storage`, `cdrom`, `hid`, `ncm`, `rndis`,
`nat`, `nat6`) is `1` (ok), `0` (missing) or `-1` (works with caveats), plus `version`,
`udc_name` and `requirements_met`. The exit code is the same as the text report's.

`stop` is safe at any time, any number of times. It is also the recovery path: if a
command was killed half-way, or the phone lost power, `stop` finishes the job.

## storage

```sh
usb-arsenal storage start [--mode MODE] [--lun N] IMAGE
usb-arsenal storage eject [--lun N]
usb-arsenal storage stop
```

| Mode | The host sees |
|---|---|
| `auto` (default) | a disk if the image has an MBR or GPT (hybrid Linux ISOs, USB images); otherwise a CD-ROM, or `dvd` when the image is over the CD-ROM limit |
| `cdrom` | a read-only CD-ROM: plain ISO9660/UDF images boot this way |
| `dvd` | a read-only disk with 2048-byte sectors: plain ISOs of any size, such as Windows ISOs |
| `disk` | a read-only disk |
| `rw` | a writable disk: a pen drive the host can write to |

- **`--lun N` (0-7)** adds another drive. Each LUN is a separate drive on the host.
- **`start` on a live LUN** swaps the image in place, like changing a disc.
  - A change between disk, `dvd` and CD-ROM re-plugs the gadget, because a host only
    reads the device type and sector size when it enumerates or sees a new disc.
  - If the host has locked the drive, `start` also re-plugs, which releases the lock.
- **CD-ROM mode stops at about 2.36 GB.** The kernel cuts larger images off and the host
  reads a truncated disc; USB Arsenal warns (unless the kernel carries
  the DVD patch from `kernel-patches/`). `dvd` mode has no limit and is what auto mode picks for a
  big plain ISO:
  - The image goes behind a read-only loop device with 2048-byte blocks. The loop
    detaches by itself on eject, swap or stop.
  - UEFI firmware boots it through El Torito, and Windows finds its UDF volume because
    the sectors are the size UDF expects. Verified with a 4.9 GB WinPE 11 ISO.
  - It needs kernel 4.14 or newer, and on Android an SELinux rule that lets the kernel
    read loop devices. The module loads that rule at boot; with the bare binary, run
    `ksud sepolicy patch "allow kernel loop_device blk_file { open read getattr ioctl }"`
    (Magisk: `magiskpolicy --live "..."`). Without it, `storage` refuses instead of
    serving a disk the host cannot read.
  - It depends on the firmware. A 2021 MSI laptop's UEFI booted it; a 2012 Samsung
    NP300E did not even list it, in UEFI or legacy mode. A BIOS boots a USB disk from
    its MBR, which a plain ISO does not have, and older firmware may not take 2048-byte
    sectors on a disk. For older PCs, use `--mode cdrom` on a kernel with
    `kernel-patches/4.14/0004` to `0006` (or `6.12/0001`), which booted both laptops.
- **Through the daemon, the image is opened on your side** and passed to the daemon as an
  open file, so a path inside a container works as typed.
- **On Android, an image the kernel may not read gets relabelled.** Files in a container
  or in `/data/local/tmp` carry an SELinux label the kernel's mass-storage thread cannot
  read, so USB Arsenal relabels them to the label `/sdcard` files use, and says so.

Make a blank 4 GB pen drive: `truncate -s 4G pendrive.img`, then
`usb-arsenal storage start --mode rw pendrive.img` and format it from the host.

## hid

```sh
usb-arsenal hid start [--perm MODE] [--group GROUP]
usb-arsenal hid stop
```

`start` adds a boot-protocol keyboard and a 5-button mouse with a wheel, then prints
which `/dev/hidgN` is which. The numbers depend on what else the kernel has; Android's
own HID function may already hold `hidg0`.

| Device | Report |
|---|---|
| keyboard | 8 bytes: modifiers, 0, then up to six HID key codes |
| mouse | 4 bytes: buttons, X, Y, wheel (signed) |

```sh
printf '\0\0\x04\0\0\0\0\0' > /dev/hidg0   # press 'a'
printf '\0\0\0\0\0\0\0\0' > /dev/hidg0     # release
printf '\0\x0a\0\0' > /dev/hidg1           # mouse 10 px right
```

The nodes are `0660 root` by default. `--perm` and `--group` (a name, or a number on
Android) widen that, and the setting is re-applied each time the gadget re-plugs.
Anyone who can write a node can type on the host.

## tether

```sh
usb-arsenal tether start [--rndis] [--address CIDR] [--dns IP[,IP]] [--upstream IFACE]
usb-arsenal tether start --gateway CONTAINER [--gateway-iface IFACE]
usb-arsenal tether start --no-net
usb-arsenal tether stop
```

The host gets a network adapter, an address by DHCP and a route out through the phone's
connection. The default is `192.168.42.129/24` for the phone, with the host on `.130`,
plus IPv6 by SLAAC from `fd75:7361::/64` where the kernel has IPv6 NAT.
`--gateway` hands the link to an OpenWrt container instead. [tethering.md](tethering.md)
covers both modes and the host side.

## identity

```sh
usb-arsenal identity generate ~/my.conf   # every setting, with its default
usb-arsenal identity set ~/my.conf        # make it active
usb-arsenal identity show
usb-arsenal identity unset
```

See [identity.md](identity.md).

## daemon

```sh
usb-arsenal daemon [--foreground] [--gid GID]
usb-arsenal daemon stop
```

See [containers.md](containers.md). When a daemon is running, every command except
`daemon`, `help`, `version` and `identity generate` runs through it. `UA_NO_PROXY=1`
makes a command act directly.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | done |
| 1 | it failed: a missing kernel feature, a refused write, a busy port |
| 2 | usage error: unknown command, action or option, or a bad value |
