# Architecture

USB Arsenal drives the kernel's USB gadget subsystem through configfs and sysfs, and
nothing else. The kernel implements every USB function; the code here creates
functions, writes their attributes, links them into a config, binds the gadget to the
controller, and always hands the controller back. Research behind the decisions is in
[research/](research/), and progress is in [ROADMAP.md](ROADMAP.md).

## Source layout

| File | Role |
|---|---|
| `src/include/usbarsenal.h` | the one header every `.c` includes |
| `src/main.c` | global options, command table, help, the client relay |
| `src/gadget.c` | the gadget core: open, begin, add, commit, teardown, hand-back |
| `src/check.c` | capability probing, `check`, the NCM safety rule |
| `src/daemon.c` | the daemon, its authorisation, sessions, and the client half |
| `src/config.c` | profiles: loader, `generate`, `set`, the active profile |
| `src/utils.c` | logging, file and process helpers, workspace paths, fd passing |
| `src/gadgets/{storage,hid,tether}.c` | the three commands |
| `src/net/` | netlink, DHCP, RA, iptables, uplink and netns helpers lifted from Droidspaces (`ds_` names kept so they can be re-synced) |
| `module/` | the KernelSU/Magisk module (`make module`) |
| `kernel-patches/<version>/` | `git format-patch` exports of kernel fixes |

## The gadget core (`src/gadget.c`)

Every command follows the same steps: `ua_gadget_open` → `begin` → `add` (one or more
functions, attributes written while unlinked) → `commit`. Failures go to `teardown`.
- **`open`:**
  - finds configfs (mounting it and loading `libcomposite` if needed);
  - picks the UDC (one is used automatically; more than one needs `--udc`);
  - decides whether Android owns the controller, from the kernel (`g1` plus
    `/sys/class/android_usb`), not from `/system`, so it also works inside a container;
  - picks the backend.
- **`begin`:**
  - enters the critical section: the stop signals are held, and a flock on
    `Gadget/lock` serialises concurrent commands;
  - on the first take, writes the hand-back record, `Gadget/handoff`, and fsyncs it
    *before* the controller changes hands;
  - releases Android: `setprop sys.usb.config none` where the property service exists,
    then unbinds `g1` by hand if it is still bound;
  - on later calls, it only unbinds.
- **`commit`:**
  - links every `<type>.ua*` function;
  - binds;
  - raises the pullup (below);
  - waits for `configured`;
  - re-applies the HID permissions.

  A link failure fails the commit; no functions left means `teardown`.
- **`teardown`:**
  - stops the tether daemon;
  - unbinds and removes everything of ours;
  - restores the previous owner: the saved `sys.usb.config`, or `g1` rebound by hand
    once its functions are linked, never racing init.

  The hand-back record is deleted only once Android's gadget is really bound again, so a
  failed restore leaves it for the next `stop`.
- **Boot marker:** the record carries the boot_id (or the btime as a fallback), and a
  record from an earlier boot is ignored.
- **SIGHUP and SIGPIPE are ignored** process-wide, because releasing Android kills adbd
  and the shell the command may be running from.

### Backends

- **Own gadget (`usb_arsenal`):** a separate configfs gadget. Used whenever a second
  gadget can be created.
- **Piggyback:** stock Samsung 4.14/4.19 configfs cannot create a second gadget (every
  gadget registered its uevent device as a hardcoded `android0`, so the `mkdir` failed as
  ENOMEM). It also staged symlinked functions in `linked_func`, and only a write to
  `android0/functions` moved them into the bind list. So the piggyback backend:
  1. creates its functions inside Android's `g1` with the `ua` instance prefix;
  2. links them;
  3. writes `enable 0`, then the function types (plus `adb` if it was on) to
     `android0/functions`, then the UDC, then `enable 1`;
  4. on teardown, removes them while they are still in `func_list`, restores the saved
     functions, device class and `enable`.

  Android rewrites `g1` on any `sys.usb.config` change, which is the backend's known
  limit. Patches `0001` and `0002` in `kernel-patches/4.14/` remove the need for it.

### Pullup: the Android HACK, and a trap

Android's UDC core comments out the pullup after bind on some kernels, so the host sees
nothing until userspace connects. `soft_connect` raises it, but **on 4.14 cores "connect"
calls `usb_gadget_udc_start()` unconditionally**. On an already started dwc3 that frees
the controller IRQ while in use, and the next unbind panics (seen on the S10).

`pullup()` therefore does nothing when the UDC state leaves "not attached" within 1 s,
and otherwise writes the balanced pair `disconnect`, then `connect`. Never write
`connect` alone.

### android0 and gadget removal: a third trap

Android's configfs (`CONFIG_USB_CONFIGFS_UEVENT`) gives every gadget an `androidN` device
in `/sys/class/android_usb`, all created with devt `MKDEV(0, 0)`. `android_device_destroy()`
removes the dropped gadget's attribute files, then calls
`device_destroy(class, gi->dev->devt)`, which looks the device up by devt and finds the
oldest: `android0`, owned by `g1`. So removing any second gadget destroys Android's device,
and `UsbDeviceManager`, which listens for `DEVPATH=.../android0`, goes deaf until a reboot.
ADB keeps working (FunctionFS), which hides it; the USB notification and USB tethering do
not. Found on the a166p after about 110 gadget drops (each command's create-and-remove
test of `usb_arsenal`, check's probe gadget, every `stop`) had left `android109` and
`android110`, both without attributes.

`keep_gadget()` is true whenever `/sys/class/android_usb` exists. Then `ua_gadget_open`
creates `usb_arsenal` once and keeps it, probes run inside it, and teardown unbinds it and
removes our functions and links but not the directories. `ua_gadget_in_use()` (bound, or
links in `c.1`) replaces "the directory exists" for `stop` and the crash-recovery rebind.
`kernel-patches/4.14/0001` fixes the kernel side with `device_unregister(gi->dev)`.

### Samsung f_ncm: another trap

`CONFIG_USB_ANDROID_SAMSUNG_COMPOSITE` NCM keeps per-system state, and a second NCM
instance next to Android's `ncm.0` destroys `android0` and panics. `ua_ncm_unsafe()`
(`src/check.c`) is true when `android0/terminal_version` exists without
`android0/ncm_instances` (the marker patch `0003` adds). When it is true, no NCM
directory is created, not even to probe, and `tether` uses RNDIS.

Samsung's f_ncm also creates its netdev only at bind, and the `u_ether_configfs` stores
(`dev_addr`, `host_addr`, `qmult`) dereference it unchecked. So `tether` reads `dev_addr`
before writing the MACs, and skips them if the read fails. Patch `0003` gives the netdev
to the instance, as mainline does.

## Capability probing (`src/check.c`)

Each function is probed by creating `<type>.arsenal_probe` and removing it, in the
gadget we would use or in a throwaway one. The kernel loads `usb_f_<type>` on demand, so
modular builds count. The probe instance does not start with `ua`, so a probe left
behind by a crash is never linked. `ua_check_requirements` is the quick gate before each
action; `check` prints the full report.

## The daemon (`src/daemon.c`)

A port of Droidspaces' daemon with two deliberate differences:
- **A filesystem socket, `Run/usb-arsenal.sock`,** instead of an abstract one, which
  would be confined to one network namespace.
- **Authorisation without Droidspaces' same-pid-namespace rule,** which would refuse
  every container.

Details:
- **Authorisation** (fail closed): `SO_PEERCRED` uid 0; or gid 3460 as the primary
  group, in `SO_PEERGROUPS`, or in `/proc/<pid>/status` `Groups:` when the peer shares
  our pid namespace. The socket is `0660 root:<gid>` in a `0750` `Run/`, which is the
  first gate.
- **Wire format:**
  - request: `u32 "UARS" | u16 version | u16 nfds | u32 argc`, then length-prefixed
    argv, with up to 8 fds on the first `sendmsg`;
  - replies: frames `u8 type | u32 len | payload` (OUT, ERR, EXIT).
- **Sessions:** fork per client; the command re-executes the pinned binary with
  `UA_NO_PROXY=1`, the passed fds as 3, 4, ..., and stdout/stderr piped back. A client
  hangup sends the command SIGTERM.
- **Relay rules** (`src/main.c` `relay()`):
  - everything is relayed except `daemon`, `help`, `version`, `--help` and
    `identity generate`;
  - `storage start` opens IMAGE on the client side and swaps it for `--fd=3`; the kernel
    is given `/proc/self/fd/N`, which follows the magic link to the real file;
  - `identity set` sends its file the same way plus `--path=<client realpath>`, and the
    daemon symlinks when `fstat(fd)` matches `stat(path)`, else keeps a copy.
- **SIGTERM** removes the socket and pid file but leaves the gadget alone.

## The workspace

`/data/local/USB-Arsenal` on Android, `/var/lib/USB-Arsenal` on Linux and in containers,
laid out like Droidspaces' (`Logs`, `Pids`, `Net`, plus `Gadget`, `Run`, `bin`).
- `ua_ws_path()` creates directories only as root; a non-root client only gets paths.
- The workspace must be root-owned and not group- or world-writable, or the command
  refuses before anything changes hands.

## Profiles (`src/config.c`)

One table of keys drives loading, validation, `generate` and `show`. The loader follows
Droidspaces' `config.c`:
- `key=value` lines and `#` comments;
- a bad value or an unknown key warns and keeps the default;
- writes are atomic (mkstemp, fsync, rename, then fsync the directory).

The active profile is `Gadget/identity.conf` (a symlink, or a copy). `build_skeleton()`
reads it on every take and rebind; the commands read their defaults from it.

## The tether process (`src/gadgets/tether.c`)

`tether start` forks the network side and waits on a pipe for it to report up or down.
Its main loop is Droidspaces' route monitor with our own subnets and interface: it
listens for rtnetlink rule, route, link and address events (a netd default-network swap
is a rule event), lets a burst settle for 200 ms, then reprobes. A 1.5 s heartbeat
reprobes too, for kernels with broken notifications. Each reprobe:
- notices a re-created netdev (a new ifindex, M62 frees it on unbind), and brings it
  up, re-adds the addresses and restarts DHCP;
- re-adds the Android policy rules and, when `ds_ipt_host_rules_present()` says one is
  missing, the firewall rules (netd flushes both when it restarts);
- follows the uplink, moving the IPv4 and IPv6 FROM rules. Automatic mode keeps a rule
  through a transient loss; a pinned `--upstream` that disappears loses it.

Forwarding is re-asserted on every pass, on Linux as on Android, and never turned back
off: a Droidspaces container or Android's own tethering may be routing through it. The
two monitors coexist because every rule either one touches carries its own subnet or
interface; only the MSS clamp is shared, and nobody removes it.

In gateway mode the reprobe instead keeps `usb0` on the `ua-usb` bridge and the veth plugged into
the container's current namespace. The veth is created with its far end born there,
under its final name, and rebuilt when the container's namespace inode changes.
