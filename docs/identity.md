# Identity profiles

A profile is an editable `key=value` file holding everything a user may want to change:
the IDs, strings and serial the host sees, and the defaults of each command. Keep as many
as you like, anywhere; one is active at a time. Code: `src/config.c`. The format and
loader follow Droidspaces' `config.c`:
- `#` comments, trimmed keys and values;
- a bad value or an unknown key is warned about and the default kept;
- writes are atomic (mkstemp, fsync, rename).

```sh
usb-arsenal identity generate ~/pixel.conf    # every key, with its default
usb-arsenal identity set ~/pixel.conf         # make it the active profile
usb-arsenal identity show                     # values in force and their source
usb-arsenal identity unset                    # back to the built-in defaults
```

A command-line option always wins over the profile: `tether start --address ...`
ignores `tether_address`.

## Keys

| Key | Default | Notes |
|---|---|---|
| `id_vendor`, `id_product` | `0x1d6b`, `0x0104` | Linux Foundation multifunction composite |
| `bcd_device`, `bcd_usb` | `0x0100`, `0x0200` | |
| `manufacturer`, `product`, `configuration` | `USB Arsenal` | printable ASCII, at most 126 characters |
| `serial` | empty | empty: `ro.serialno`, or `/etc/machine-id` |
| `max_power` | `250` | mA, 0 to 500 |
| `storage_inquiry` | empty | SCSI vendor(8) product(16) revision(4), at most 28 characters; empty: the kernel's "Linux File-Stor Gadget" |
| `tether_dev_mac`, `tether_host_mac` | empty | empty: derived from the device seed, stable |
| `storage_mode` | `auto` | `auto`, `cdrom`, `dvd`, `disk`, `rw` |
| `tether_function` | `ncm` | `ncm`, `rndis` |
| `tether_address` | `192.168.42.129/24` | this side; the host gets the next address |
| `tether_dns` | empty | empty: `1.1.1.1,8.8.8.8` |
| `tether_upstream` | empty | empty: detect automatically |
| `hid_perm`, `hid_group` | `0660`, `0` | octal; numeric gid |

## Where the active profile lives

`<workspace>/Gadget/identity.conf` (`/data/local/USB-Arsenal/...` on Android,
`/var/lib/USB-Arsenal/...` on Linux) can be one of three things:
- **A symlink to your file.** Edits take effect at the next bind, with no further `set`.
- **A copy, when the daemon cannot see your file.** The usual case is a path private to
  a container (`/root/pixel.conf`). The client relay sends the file open, and the daemon
  checks whether the path it was given reaches the very same file (same device and
  inode). If not, it keeps a copy with a `# copied from <path>` header. Run `set` again
  after editing.
- **Missing.** The built-in defaults are used, silently.

If the symlink's target disappears, every command says
`Profile <path> is gone; using the built-in defaults` and carries on with the defaults.

`identity set` while the gadget is live re-plugs it once, so the host sees the new IDs
at once. `generate` never goes through the daemon: it only writes your own file and
needs no root.

## Things to know

- `lsusb` names a device from its own usb.ids database (`1d6b:0104` reads as "Linux
  Foundation Multifunction Composite Gadget"). The strings the device actually sends are
  under `iManufacturer`, `iProduct` and `iSerial` in `lsusb -v`, and Windows and macOS
  show those.
- Mass storage, HID and NCM bind by class, and RNDIS by its Microsoft OS descriptor, so
  any VID/PID works on every host. A borrowed real vendor ID (Google `18d1`, Samsung
  `04e8`) works too, but it belongs to that vendor, so that is your call. A host with
  that vendor's driver for that exact PID may try to bind it.
- The piggyback backend (stock Samsung kernels) rides in Android's `g1`, whose IDs and
  strings stay Android's. The command defaults still apply.
