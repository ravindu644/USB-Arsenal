[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](./LICENSE)

# USB Arsenal

USB Arsenal turns a rooted Android phone, or a Linux board with a USB device port, into
several USB devices at once over one cable: a USB drive or CD-ROM holding any image, a
network adapter that shares the phone's internet, and a keyboard and mouse that a program on the phone can
drive.

**There is no app and no kernel module to install.** One static binary drives the
kernel's own USB gadget drivers through configfs, and always gives the USB port back to
Android when it is done, so ADB and MTP return.

## What you get

- **ISOs the way a boot stick or DVD presents them.** An image with a partition table
  (hybrid Linux ISOs, USB images) appears as a disk; a plain ISO9660 image appears as a
  CD-ROM, or past the 2.36 GB CD-ROM limit (Windows ISOs) as a disk with 2048-byte
  sectors. The mode is picked from the image, and a new image can be swapped in while
  the drive is live.
- **A pen drive.** Any image file or block device, read-only or writable, with up to
  eight drives at once.
- **USB tethering.** NCM, or RNDIS for older Windows, with DHCP and NAT built in, IPv6
  included where the kernel can NAT it. Or hand the link to an OpenWrt container and let
  it be the router.
- **A keyboard and a mouse.** `/dev/hidg*` nodes that any program can write reports to.
- **All of it at once.** Storage, network and HID share one cable and survive each other
  being added or removed, and a cable replug.
- **Works from containers.** A root daemon on the Android side owns the port, so the same
  commands run from a Droidspaces container, network included.
- **Your own identity.** Vendor and product IDs, names, serial number and defaults come
  from editable profile files.
- **Fails safe.** The port is handed back after every command, error, crash or kill, and
  `usb-arsenal stop` recovers whatever state was left.

## Requirements

**Android**

- A rooted device. **KernelSU is recommended**.
- A kernel built with `CONFIG_USB_CONFIGFS` and the functions you want. Run
  `usb-arsenal check` to see what yours has.
- Samsung 4.x kernels need care: stock ones run in a fallback mode, and NCM is avoided
  unless the kernel carries our fix. See [kernel requirements](docs/kernel-requirements.md),
  which also ships the patches.

**Linux**

- A board whose USB port can act as a device (a Raspberry Pi's USB-C or OTG port, for
  example), configfs, and root.

## Get started

**Android**

1. Build the module with `make module`, or take the zip from a release.
2. Flash `usb-arsenal-<version>.zip` in KernelSU and reboot. The daemon starts at boot.
3. Run `usb-arsenal check` in a root shell.

**Linux**

Build with `make native` (or cross-compile with `make aarch64` / `armhf`), copy
`output/usb-arsenal` to the board, and run `sudo usb-arsenal check`.

The [installation guide](docs/installation.md) covers both in detail.

## Examples

```sh
usb-arsenal storage start ubuntu-24.04.iso       # a disk (or CD-ROM) on the PC
usb-arsenal storage start --mode rw pendrive.img  # a writable pen drive
usb-arsenal hid start                             # /dev/hidg0 and /dev/hidg1
usb-arsenal tether start                          # the PC gets internet over USB
usb-arsenal tether start --gateway OpenWrt        # let an OpenWrt container serve it
usb-arsenal status
usb-arsenal stop                                  # everything off, ADB and MTP back
```

## Tested on

- Galaxy A16 5G (MediaTek, kernel 5.15), from Android and from a Droidspaces container.
- Galaxy S10 on the M62-backport kernel (Exynos 9820, kernel 4.14).

The host was a Fedora PC: it read the disks and CD-ROMs, typed nothing but saw the HID
devices, and got online through the tether. An MSI laptop booted Alpine Linux from an
ISO on the S10 (UEFI, as a disk and as a CD-ROM) and a 4.9 GB Windows PE ISO in `dvd`
mode. A 2012 Samsung laptop booted the same ISO as a DVD-ROM (patched kernel) in legacy
BIOS and UEFI mode, but not in `dvd` mode, which needs newer firmware. The KernelSU module
was flashed on the S10 and starts the daemon at boot. Other phones, kernels and hosts are
untested; reports are welcome.

## Security

> [!WARNING]
>
> USB Arsenal runs as root and controls the phone's USB port.
>
> Anyone who can write to a `/dev/hidg*` node can type on the computer it is plugged
> into. The nodes are `0660 root` by default; widen that only on purpose.
>
> The daemon accepts root and members of the `usb-arsenal` group (gid 3460). Membership
> means control of the USB port, so grant it like `sudo`.
>
> A profile can borrow a real vendor's USB IDs. That is your choice to make.

## Documentation

| Page | What it covers |
|------|----------------|
| [Installation](docs/installation.md) | The KernelSU module, a manual install, Linux boards. |
| [Usage](docs/usage.md) | Every command and option, with examples. |
| [Tethering](docs/tethering.md) | NAT mode, OpenWrt gateway mode, the host side. |
| [Containers](docs/containers.md) | The daemon, and using USB Arsenal from inside a container. |
| [Identity profiles](docs/identity.md) | Vendor and product IDs, names, serial, defaults. |
| [Kernel requirements](docs/kernel-requirements.md) | Kernel options, Samsung quirks, the patches in `kernel-patches/`. |
| [Troubleshooting](docs/troubleshooting.md) | Symptoms, causes and fixes. |

Developer notes (architecture, kernel research, roadmap) live in
[docs/developer](docs/developer/).

## Credits

- The Linux USB gadget subsystem: `f_mass_storage`, `f_hid`, `f_ncm`, `f_rndis` and
  configfs do the real work.
- [Droidspaces](https://github.com/ravindu644/Droidspaces-OSS): the networking code
  (netlink, DHCP, NAT) and the daemon design come from it, by the same author.
- VirtualAP, whose managed mode inspired the OpenWrt gateway mode.
- DriveDroid, which showed a phone can boot a PC.

## License

[GNU General Public License v3.0](./LICENSE). Copyright (C) 2026
[ravindu644](https://github.com/ravindu644).
