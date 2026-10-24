# The Android app

The app is a front for the same `usb-arsenal` binary. It installs the backend, and every switch
and key in it runs a `usb-arsenal` command through root. The phone's port is never held by the
app itself. Everything it does, a root shell can do too ([usage.md](usage.md)).

## Requirements

- Android 8.0 or later, rooted. KernelSU is recommended.
- A kernel `usb-arsenal check` accepts ([kernel-requirements.md](kernel-requirements.md)).
  The app runs the check for you before it installs anything.

## Install

Take `usb-arsenal-v<version>-<hash>.apk` from a [release](https://github.com/ravindu644/USB-Arsenal/releases)
and install it. Every build of a version updates the one before it.

The first launch walks through setup:

1. **Root.** Grant the app root when your root manager asks. If you deny it, grant it in the
   manager and reopen the app.
2. **Controller.** Only on a phone with more than one USB device controller. Pick the one your
   cable is in; the happy Pin marks the one a computer is using. Settings can change it later.
3. **Check.** The bundled binary probes the kernel. A `missing` or `caveat` line says what it
   costs; a phone that cannot run USB Arsenal stops here, with the reason.
4. **Backend.** Installs the binary to `/data/local/USB-Arsenal/bin/usb-arsenal` and the boot
   module. When the module had no SELinux rule before, it says to reboot once.

## Use

**Home** shows what is up. Each function has a row that opens its screen and a switch that
starts or stops it with what its screen set up. The bar at the bottom says who has the port:
Android, or the gadget and which controller. **Stop all** stops everything and gives the port
back to Android, so ADB and MTP return.

**Storage** serves images from `/sdcard/USB-Arsenal/Disks`: files ending in `.iso`, `.img`,
`.bin`, `.raw`, `.ima` or `.dd`. Other files are not listed, and **Pick image** refuses them.
Pick a LUN on the strip (up to eight), an image for it, and how the host sees it:

| Mode | The host sees |
|---|---|
| `auto` | a disk if the image has a partition table, else a CD-ROM, or `dvd` past the CD-ROM limit |
| `cd` | a CD-ROM |
| `dvd` | a read-only disk with 2048-byte sectors, for plain ISOs of any size (Windows ISOs) |
| `disk` | a read-only disk |
| `rw` | a writable disk |

[usage.md](usage.md#storage) has the details.

**Create** makes a new image there (sparse in an instant, or written out in full). **Pick
image** opens the system picker; a picker that gives no real file path is refused, so copy the
image into the folder instead.

**Tether** shares the phone's internet with the host: NCM, or RNDIS for older Windows, with
the address and DNS you set, and the upstream picked from the phone's uplinks (every
interface with a default route), or `auto`. With [Droidspaces](https://github.com/ravindu644/Droidspaces-OSS)
installed, gateway mode hands the link to one of its running containers instead
([tethering.md](tethering.md)).

**HID** sets the report interval and opens **Keys + mouse**: a trackpad with a wheel strip and
L M R buttons, and a keys tab with F1 to F12, the editing, modifier and arrow keys, and a box
whose text the host receives as typed. Speed and Natural scroll sit under the trackpad. The
full-screen button turns the phone into one wide trackpad in landscape. A gamepad's left
stick moves the pointer; A and X click.

**Log** shows the backend's log: every command run from the app or a shell and what it printed,
the tether process's lines too, in time order, kept across launches. Clear empties it.

**Settings**: motion, haptics and the theme (eleven two-colour LCD looks, Amber by default),
the identity profile ([identity.md](identity.md); profiles
live in `/sdcard/USB-Arsenal/Profiles`), the controller on phones with several,
`usb-arsenal` on PATH for root shells, Requirements (the kernel check again, after a kernel
update) and Reinstall.

**What each screen sets up is the backend's.** The picks, the tether options, the HID interval
and the controller live in `/data/local/USB-Arsenal/Gadget/setup.conf`, the same file
`usb-arsenal setup` edits ([usage.md](usage.md#setup)), so a setup made in the app starts from
a root shell with a bare `usb-arsenal storage start`, one made there shows in the app, and
clearing the app's data or reinstalling it loses nothing. Each screen draws what it last knew
at once and corrects it when the backend answers; after three unanswered reads Home's bar says
`no answer from usb-arsenal` and keeps showing the last answer.

## Without touch

Every screen works with a keyboard, a D-pad or a gamepad: Tab or the D-pad moves, Enter or A
presses, Back, Escape or B goes back, and L1 and R1 step the focused segments. TalkBack reads
each control by name and state. The app follows the system's font size and its "Remove
animations" setting, and has its own switches for motion and haptics.

## Tested on

The Galaxy A16 5G (kernel 5.15) and the Galaxy S10 (kernel 4.14) listed in the
[README](../README.md#tested-on), with a Fedora PC as the host.
