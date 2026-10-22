# Installation

USB Arsenal is one static binary. On Android the KernelSU module installs it and starts
the daemon at boot. On Linux you copy the binary.

## Android: the KernelSU module

1. Build the zip, or take it from a release:
   ```sh
   make module          # output/usb-arsenal-v<version>.zip, every architecture inside
   ```
2. Flash it in KernelSU and reboot. (The module follows the standard KernelSU/Magisk
   layout, but it has not been flashed on a test device yet; the daemon it starts was
   tested by starting it by hand.)
3. In a root shell:
   ```sh
   usb-arsenal check
   usb-arsenal version  # "mode: daemon (...)" once the daemon is up
   ```

What the module does:
- **Installs the binary for the device's ABI** as `/data/local/USB-Arsenal/bin/usb-arsenal`
  and links `/system/bin/usb-arsenal` to it, so after the reboot `usb-arsenal` works from any
  root shell. (Installed from the app, the link is a Settings switch.)
- **Starts the daemon at boot** (`service.sh`) while daemon mode is on: the marker
  `/data/local/USB-Arsenal/.daemon_mode` holds `1`. The zip turns it on; installed from the
  app, it is a Settings switch. The daemon owns the USB port, so the same
  commands also work from containers; see [containers.md](containers.md).
- **Gives the port back on uninstall** and stops the daemon.

## Android: by hand

```sh
make aarch64                        # or armhf / x86_64 / x86
adb push output/usb-arsenal /data/local/tmp/
adb shell su -c "/data/local/tmp/usb-arsenal check"
```

Without the daemon, each command acts directly. You can start the daemon yourself with
`usb-arsenal daemon`.

Taking the USB port from Android stops ADB over USB until `usb-arsenal stop`. Run
commands from a root terminal on the phone, from a container over SSH, or over ADB with
`nohup` so the command finishes after ADB drops.

## Linux boards

The board's USB port has to be able to act as a device. On a Raspberry Pi that is the
USB-C port of a Pi 4 or 5, or the data micro-USB port of a Zero:

```
# /boot/firmware/config.txt
dtoverlay=dwc2
```

USB Arsenal mounts configfs and loads `libcomposite` itself when they are missing. Then:

```sh
make native          # or make aarch64 / armhf to cross-compile
sudo ./output/usb-arsenal check
```

On Linux the workspace is `/var/lib/USB-Arsenal`. If another gadget already owns the
port, USB Arsenal names it and refuses to touch it.

## Building

Builds are static, against musl. `make` looks for `<triplet>-gcc` in `$MUSL_CROSS`,
`~/toolchains/<triplet>-cross/bin`, `PATH` and `/opt/cross/bin`.

| Target | Output |
|---|---|
| `make native` | this machine's architecture |
| `make aarch64`, `armhf`, `x86_64`, `x86` | cross builds into `output/usb-arsenal` |
| `make module` | the KernelSU/Magisk zip with all four |

## Uninstall

Remove the module in KernelSU: its uninstall script runs `usb-arsenal stop` and stops
the daemon. For a manual install, run `usb-arsenal stop`, then `usb-arsenal daemon stop`,
and delete the binary and `/data/local/USB-Arsenal` (or `/var/lib/USB-Arsenal`).
