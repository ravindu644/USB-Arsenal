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
| `auto` (default) | a disk if the image has an MBR or GPT (hybrid Linux ISOs, USB images), a CD-ROM otherwise |
| `cdrom` | a read-only CD-ROM: plain ISO9660/UDF images boot this way |
| `disk` | a read-only disk |
| `rw` | a writable disk: a pen drive the host can write to |

- **`--lun N` (0-7)** adds another drive. Each LUN is a separate drive on the host.
- **`start` on a live LUN** swaps the image in place, like changing a disc.
  - A change between disk and CD-ROM re-plugs the gadget, because a host only reads the
    device type when it enumerates.
  - If the host has locked the drive, `start` also re-plugs, which releases the lock.
- **CD-ROM mode stops at about 2.36 GB.** The kernel cuts larger images off and the host
  reads a truncated disc; USB Arsenal warns. Modern Windows ISOs are bigger than that:
  write them to a disk image with an NTFS partition (Rufus, or woeusb on Linux) and use
  `--mode disk`.
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
