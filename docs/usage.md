# Usage

```
usb-arsenal [--udc NAME] [-v] <command> [<action>] [options] [args]
```

Every command explains itself: `usb-arsenal <command> --help`, `usb-arsenal help
<command>` and a bare `usb-arsenal <command>` all print the same page with examples.
A command's options may come before or after its action.

| Global option | Meaning |
|---|---|
| `--udc NAME` | The USB device controller to use. Only needed when `/sys/class/udc` lists more than one. `dummy_udc.*` (built into every GKI kernel) is used only when this names it. |
| `-v`, `--verbose` | Debug output |
| `-h`, `--help`, `-V`, `--version` | Help, version |

There is one gadget. Each command adds or removes its part of it, so storage, network
and HID can run together. Adding or removing a part re-plugs the gadget for a moment,
and the host sees it re-appear with the rest intact. Removing the last part hands the
USB port back to Android. Defaults for every option come from the active
[identity profile](identity.md); an option given on the command line wins.

**What each function starts with is saved.** A `start` with arguments writes them into the
setup (`<workspace>/Gadget/setup.conf`), a bare `start` runs what is saved there, and `apply`
brings what runs to it. The Android app edits the same file, so a setup made on either side
is there for the other, and clearing the app's data loses nothing. See [setup](#setup).

## check, status, stop

```sh
usb-arsenal check     # what this kernel can do; MUST HAVE lines block every command
usb-arsenal status    # what is live: storage images, HID nodes, the tether link
usb-arsenal stop      # everything off, the port back to its owner
```

`check --json` prints the same probes as one JSON object for a program to read: each key
(`root`, `configfs`, `udc`, `gadget`, `mass_storage`, `cdrom`, `hid`, `hid_interval`, `ncm`,
`rndis`, `nat`, `nat6`) is `1` (ok), `0` (missing) or `-1` (works with caveats), plus `version`,
`udc_name` and `requirements_met`. `hid_interval` says whether `hid --interval` takes (the
f_hid patch in [kernel-requirements.md](kernel-requirements.md)); it is `0` whenever `hid` is.
The exit code is the same as the text report's.

`status --json` prints the live gadget as one JSON object, every key always present:

| Key | Value |
|---|---|
| `version`, `udc`, `udc_state` | the binary's version, the controller, its state (`configured`, `not attached`, ...) |
| `bound` | `1` while our gadget holds the controller, `0` when it is with its owner |
| `speed` | what the host negotiated, the UDC's `current_speed` (`full-speed`, `high-speed`, `super-speed`, ...); empty while not bound. The text form adds it to the `Controller:` line |
| `storage` | one object per LUN: `lun`, `file`, `mode` (`cdrom`, `dvd`, `disk`, `rw`), `access` (`ro`, `rw`); an ejected LUN has empty strings |
| `hid` | one object per node: `function` (`keyboard`, `mouse`), `node` (`/dev/hidgN`, empty until it appears), `interval` (the poll interval exponent it runs with: `0` for the kernel's default, 1 ms; `-1` where the kernel has no control for it) |
| `tether` | `null`, or `function` (`ncm`, `rndis`), `iface`, `running` (the network side is up), `address`, `ipv6`, `upstream`, `gateway` (`1` in gateway mode), `gateway_container`, `gateway_iface`, `gateway_waiting` (the container is not running yet), `tx_bytes` and `rx_bytes` (what the phone's end of the link has sent and received, `-1` when unread) |
| `boot_id` | this boot's id, so a reader never takes a status from before a reboot as live |
| `setup`, `running` | every key of the [setup](#setup), as set up and as the last successful start of each function used; `running` is empty for a function that is off |
| `pending` | `storage`, `tether`, `hid`: `1` when the function is linked and its setup differs from what started it (an empty value is compared as the profile's), so `<function> apply` would change something |

`stop` is safe at any time, any number of times. It is also the recovery path: if a
command was killed half-way, or the phone lost power, `stop` finishes the job.

## setup

```sh
usb-arsenal setup show [--json]            # every key: as set up, and as the last start used
usb-arsenal setup set KEY=VALUE ...        # change the setup; a bad value changes nothing
usb-arsenal setup reset [storage|tether|hid]   # back to the defaults
usb-arsenal setup revert [storage|tether|hid]  # back to what runs
```

| Key | Value |
|---|---|
| `udc` | the controller every command uses when `/sys/class/udc` lists more than one; stands in for `--udc` |
| `storage_lun0` to `storage_lun7` | `MODE:PATH` for each drive, mode `auto`, `cdrom`, `dvd`, `disk` or `rw`; empty: no drive |
| `tether_function` | `ncm` or `rndis`; empty: the profile's |
| `tether_gateway` | a Droidspaces container's name; empty: no gateway |
| `tether_gateway_iface` | the cable's name inside the container; empty: `usb0` |
| `tether_net` | `0` for `--no-net`, else `1`; ignored with a gateway |
| `tether_address`, `tether_dns`, `tether_upstream` | as the options; empty: the profile's |
| `hid_interval` | 1 to 255; `0`: the kernel's default |

`setup.conf` is `key=value` lines with `#` comments, the identity profile's format. Next to it
the backend keeps `running.conf`, the same keys as the last successful start of each function
used (cleared when the function stops, and after a reboot). A start with arguments merges them
into the setup first; `storage eject` clears that LUN in both. `status --json` reports both
files and `pending`.

## storage

```sh
usb-arsenal storage start [--mode MODE] [--lun N] IMAGE
usb-arsenal storage start [--lun N] [--mode MODE] IMAGE [--lun N] [--mode MODE] IMAGE ...
usb-arsenal storage start                  # every drive the setup holds, in one replug
usb-arsenal storage apply                  # the drives to the setup, LUN by LUN
usb-arsenal storage eject [--lun N]
usb-arsenal storage stop
```

`apply` decides against the kernel: a LUN the setup dropped is ejected in place, a LUN whose
setup changed since it started (or whose medium the host ejected) is loaded, a LUN already
serving the setup's file in its mode is left alone, and with nothing set up storage stops.
Several images on live LUNs of the same type are swapped in place in one go.

| Mode | The host sees |
|---|---|
| `auto` (default) | a disk if the image has an MBR or GPT (hybrid Linux ISOs, USB images); otherwise a CD-ROM, or `dvd` when the image is over the CD-ROM limit |
| `cdrom` | a read-only CD-ROM: plain ISO9660/UDF images boot this way |
| `dvd` | a read-only disk with 2048-byte sectors: plain ISOs of any size, such as Windows ISOs |
| `disk` | a read-only disk |
| `rw` | a writable disk: a pen drive the host can write to |

- **`--lun N` (0-7)** adds another drive. Each LUN is a separate drive on the host.
- **Several drives in one `start`:** up to eight IMAGEs, each after its own `--lun` and
  `--mode`, and the host sees all of them in one re-plug instead of one per drive. An IMAGE
  with no `--lun` takes the LUN after the one before it (the first takes 0):

  ```sh
  usb-arsenal storage start Win11.iso --mode rw pendrive.img   # LUN 0 and LUN 1
  ```
- **One file, two drives:** allowed only while both are read-only. The host caches each drive
  on its own, so a writable one would corrupt what the other shows; `start` refuses it.
- **`start` on a live LUN** swaps the image in place, like changing a disc.
  - A change between disk, `dvd` and CD-ROM re-plugs the gadget, because a host only
    reads the device type and sector size when it enumerates or sees a new disc.
  - If the host has locked the drive, `start` also re-plugs, which releases the lock.
- **`eject`** empties one LUN in place; the host sees the medium go, and the drive stays.
  Before kernel 6.6 a host's lock makes that fail, so `eject` re-plugs to release it, as
  `start` does.
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

An image whose name starts with `-` goes after `--`: `storage start -- -old.iso`. The
setup's `storage_lunN` paths are absolute and never read as options.

A swap whose new medium the kernel refuses puts the old one back. A host that holds a drive
locked (before 6.6, which has `forced_eject`) is released by one replug for every LUN
together, and every eject of one command shares a 5 s wait, so `start`, `apply` and `eject`
finish well inside a client's 30 s timeout.

Make a blank 4 GB pen drive: `truncate -s 4G pendrive.img`, then
`usb-arsenal storage start --mode rw pendrive.img` and format it from the host.

## hid

```sh
usb-arsenal hid start [--perm MODE] [--group GROUP] [--interval N]
usb-arsenal hid apply                      # restart with the setup's interval if it changed
usb-arsenal hid stop
usb-arsenal hid write NODE SIZE            # stdin's reports onto NODE, never blocking
```

`--interval` is saved as the setup's `hid_interval`; a bare `start` uses it. On a running HID
a `start` that asks for another interval fails, since the interval only changes with a restart:
`hid apply` does that in one replug and leaves the other functions bound.

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

The nodes are `0660 root` by default, or the profile's `hid_perm` and `hid_group`. `--perm`
and `--group` (a name, or a number on Android) widen that for as long as HID runs: a later
bare `start` or `apply` keeps them, `hid stop` and `stop` drop them, and the access is
re-applied each time the gadget re-plugs. Anyone who can write a node can type on the host.
Without `--interval` nothing is written to `interval`, and a function a crash left behind is
made anew, so the kernel's own default applies. Writing `0` would not do: mainline's `interval`
(6.x) takes it as a real bInterval of 0, which a host drops.

`hid write NODE SIZE` is the writer a program keeps open on a node: it reads stdin in
reports of `SIZE` bytes (1 to 64, short reads joined), and writes each to `NODE`, which must
be a character device, opened non-blocking. f_hid sends one report per write. While the host
is not reading (asleep, or slower than the reports come), only the newest unsent report is
kept and it goes out as soon as the node is writable again: the host always ends in the
latest state and never gets a backlog replayed. A report that a newer one replaces before the
host took it is never sent, so a key press needs its own poll interval before its release.
It exits `0` at the end of stdin (after one last try at the pending report, 100 ms at most),
and `1` with a message when the node cannot be opened or a write fails with anything but
`EAGAIN` (`ESHUTDOWN` once the host has gone). It takes no lock and never goes through the
daemon.

## tether

```sh
usb-arsenal tether start [--rndis | --ncm] [--address CIDR] [--dns IP[,IP]] [--upstream IFACE]
usb-arsenal tether start --gateway CONTAINER [--gateway-iface IFACE]
usb-arsenal tether start --no-net
usb-arsenal tether start                   # with the setup's options
usb-arsenal tether apply                   # restart with the setup if it changed, in one replug
usb-arsenal tether stop
```

Every option but `--gateway-pid`, `--gateway-netns` and `--foreground` is saved into the
setup; a bare `start` runs it.

The host gets a network adapter, an address by DHCP and a route out through the phone's
connection. The default is `192.168.42.129/24` for the phone, with the host on `.130`,
plus IPv6 by SLAAC from `fd75:7361::/64` where the kernel has IPv6 NAT.
`--gateway` hands the link to an OpenWrt container instead. [tethering.md](tethering.md)
covers both modes and the host side.

## identity

```sh
usb-arsenal identity generate ~/my.conf   # every setting, with its default
usb-arsenal identity set ~/my.conf        # make it active
usb-arsenal identity show [--json]
usb-arsenal identity show [--json] FILE...  # what each file gives, read as `set` reads it
usb-arsenal identity unset
```

`identity show` names a profile whose file has gone: `Profile: /path/x.conf (missing)`, then
the built-in values in force. `identity show --json` prints one object:

```json
{"profile":"/sdcard/x.conf","copy":false,"missing":"","keys":{"id_vendor":"0x1d6b",...}}
```

`profile` is the active profile (empty for the built-in defaults), `copy` is `true` when the
daemon keeps a copy of it, `missing` is the path a dangling link names, and `keys` holds every
key with the value in force, `""` for a derived one. With FILEs it prints an array, one
object per FILE: `{"path":"<realpath>","errors":N,"keys":{...}}`. Each FILE goes through the
loader `identity set` uses, so `0104` is octal as C reads it, ` key = v` spacing works, and a
missing or bad key comes back at its default; `errors` counts the bad lines (`-1`: the file
cannot be read, and the exit code is `1`). Values are printed one way: IDs as `0x%04x`
(`0x0104`), `max_power` and `hid_group` in decimal, `hid_perm` in octal with a leading `0`
(`0660`), strings as they are. `show FILE` runs in the caller's own process, with its own
permissions, even when a daemon listens.

See [identity.md](identity.md).

## daemon

```sh
usb-arsenal daemon [--foreground] [--gid GID]
usb-arsenal daemon stop
```

See [containers.md](containers.md). When a daemon is running, every command except
`daemon`, `help`, `version`, `identity generate`, `identity show FILE` and `hid write` runs
through it. A daemon whose binary is replaced on disk (an update) re-executes the new one
before it serves the next client, keeping its socket and pid, so it never needs a restart. `UA_NO_PROXY=1`
makes a command act directly. A path a container client saves into the setup (a bare `start`
later opens it on the daemon's side) must be one the daemon can see.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | done |
| 1 | it failed: a missing kernel feature, a refused write, a busy port |
| 2 | usage error: unknown command, action or option, or a bad value |
