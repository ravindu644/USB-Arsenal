<img src="docs/resources/readme/hero.png" alt="USB Arsenal: boot an ISO or share a disk, network over the USB cable, keyboard and mouse on the host" width="100%">

[![Latest release](https://img.shields.io/github/v/release/ravindu644/USB-Arsenal?label=Release&style=for-the-badge)](https://github.com/ravindu644/USB-Arsenal/releases/latest)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg?style=for-the-badge)](./LICENSE)

# USB Arsenal

USB Arsenal turns a rooted Android phone into several USB devices at once, over the one
cable: a USB drive or CD-ROM holding any image, a network adapter that shares the phone's
internet, and a keyboard and mouse that the phone drives.

The app sets everything up for you, and gives the USB port back to Android when you stop,
so ADB and file transfer work again.

## What you get

- **Boot a computer from your phone.** Put an ISO or a disk image on the phone and the
  computer sees it as a USB drive or a CD/DVD. The app picks the right one for the image.
- **A USB drive.** Share any image file with the computer, read-only or writable, up to
  eight at once.
- **Share the phone's internet.** The computer gets online through the cable. Or let a
  [Droidspaces](https://github.com/ravindu644/Droidspaces-OSS) OpenWrt container be the
  router.
- **A keyboard and mouse.** Type and move the pointer on the computer from the app.
- **All at once.** Drives, internet and keyboard share one cable, and you can turn each on
  or off without touching the others.
- **Pick how it shows up.** Choose the device name and IDs the computer sees.
- **Always recovers.** If anything goes wrong, `Stop all` gives the port back to Android.
- **Eleven LCD themes**, from the amber default to Game Boy green.

The same binary runs on a Linux board with a USB device port, and inside a privileged
[Droidspaces](https://github.com/ravindu644/Droidspaces-OSS) container.

## Requirements

- A rooted phone running Android 8 or newer.
- A kernel with USB configfs. The app checks this for you before it installs anything
  ([kernel requirements](kernel-patches/README.md)).

## Get started

1. Install the APK from the [latest release](https://github.com/ravindu644/USB-Arsenal/releases/latest),
   grant it root, and let it install the backend.
2. Put an ISO or a disk image in `/sdcard/USB-Arsenal/Disks`, open **Storage** and pick it.
3. Flip the switch on Home and plug the phone into a computer.

Everything the app does, a root shell can do with `usb-arsenal` ([usage](docs/usage.md)).
Linux boards and the KernelSU module are in the [installation guide](docs/installation.md).

## Screenshots

<table>
  <tr>
    <td width="33%" align="center"><img src="docs/resources/readme/01-home.png" width="100%"><br>Home</td>
    <td width="33%" align="center"><img src="docs/resources/readme/02-storage.png" width="100%"><br>Storage</td>
    <td width="33%" align="center"><img src="docs/resources/readme/03-tether.png" width="100%"><br>Tether</td>
  </tr>
  <tr>
    <td width="33%" align="center"><img src="docs/resources/readme/04-keys-mouse.png" width="100%"><br>Keys + mouse</td>
    <td width="33%" align="center"><img src="docs/resources/readme/05-identity.png" width="100%"><br>Identity</td>
    <td width="33%" align="center"><img src="docs/resources/readme/06-themes.png" width="100%"><br>Themes</td>
  </tr>
</table>

## Security

> [!WARNING]
>
> USB Arsenal runs as root and controls the phone's USB port.
>
> Anyone who can write to a `/dev/hidg*` node can type on the computer it is plugged
> into. The nodes are `0660 root` by default; widen that only on purpose.
>
> A profile can borrow a real vendor's USB IDs. That is your choice to make.

## Documentation

| Page | What it covers |
|------|----------------|
| [The app](docs/app.md) | Setup, every screen, keys and TalkBack. |
| [Installation](docs/installation.md) | The KernelSU module, a manual install, Linux boards. |
| [Usage](docs/usage.md) | Every `usb-arsenal` command and option, with examples. |
| [Tethering](docs/tethering.md) | NAT mode, OpenWrt gateway mode, the host side. |
| [Containers](docs/containers.md) | Running USB Arsenal inside a container. |
| [Identity profiles](docs/identity.md) | Vendor and product IDs, names, serial, defaults. |
| [Kernel requirements](kernel-patches/README.md) | Kernel options and the patches, by feature. |
| [Troubleshooting](docs/troubleshooting.md) | Symptoms, causes and fixes. |

Developer notes (architecture, kernel research, the design language, roadmap) live in
[docs/developer](docs/developer/).

## Credits

- The Linux USB gadget subsystem: `f_mass_storage`, `f_hid`, `f_ncm`, `f_rndis` and
  configfs do the real work.
- DriveDroid, which showed a phone can boot a PC.
- [Jersey 10](https://fonts.google.com/specimen/Jersey+10), [Silkscreen](https://fonts.google.com/specimen/Silkscreen)
  and [VT323](https://fonts.google.com/specimen/VT323), the app's faces, under the
  [SIL Open Font License 1.1](https://scripts.sil.org/OFL).

## License

[GNU General Public License v3.0](./LICENSE). Copyright (C) 2026
[ravindu644](https://github.com/ravindu644).
