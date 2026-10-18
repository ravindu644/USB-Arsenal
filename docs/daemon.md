# Daemon, containers and the KernelSU module

One root daemon on the host owns the USB controller, configfs, the tether
interface and NAT. Every other copy of `usb-arsenal` (an Android shell, a
Droidspaces container, a chroot) relays its command line to it over a filesystem
socket and prints what comes back. Code: `src/daemon.c`. It is ported from Droidspaces'
daemon, with two deliberate differences:

- **A filesystem socket instead of an abstract one.** An abstract socket belongs to a
  network namespace, so a container with its own could never reach it. A socket file
  crosses network namespaces, and a bind mount carries it into a container's mount
  namespace.
- **No "same pid namespace" rule.** That rule is exactly what refuses container clients
  in Droidspaces. Access is root or the `usb-arsenal` group instead.

## Workspace

| | Android | Linux, and inside any container |
|---|---|---|
| Workspace | `/data/local/USB-Arsenal` | `/var/lib/USB-Arsenal` |

The subfolders follow Droidspaces' names:

| Path | Holds |
|---|---|
| `usb-arsenald.pid` | daemon pid |
| `Run/usb-arsenal.sock` | the socket, mode `0660 root:3460`; `Run/` itself is `0750 root:3460` |
| `Logs/` | `usb-arsenald.log` (rotated at 2 MB), `tether.log` |
| `Pids/` | `tether.pid` |
| `Net/` | `tether.info` |
| `Gadget/` | `handoff`, `lock`, `hid.perm`, `identity.conf` (the active profile: a symlink, or a copy; see [identity.md](identity.md)) |
| `bin/` | a copy of the binary (installed by the module) |

## Running it

```sh
usb-arsenal daemon               # daemonizes; refuses if one is already listening
usb-arsenal daemon --foreground  # stays attached, logs to the terminal
usb-arsenal daemon stop
usb-arsenal version              # "mode: daemon (...)" or "mode: direct"
```

Every command except `daemon`, `help` and `version` first tries the daemon. If nothing
is listening, it runs directly, as before. `UA_NO_PROXY=1` forces direct mode and
`UA_SOCKET=/path` points at another socket.

**SIGTERM** stops the daemon and removes its socket and pid file. It does not tear the
gadget down; that is `usb-arsenal stop`. The gadget lives in the kernel, and the tether
network side runs as its own process.

## Who may connect

Socket permissions are the first gate: `0660 root:<gid>`. The daemon then checks
`SO_PEERCRED` and fails closed. Allowed:
- uid 0 (container root without a user namespace arrives as host root);
- primary gid equal to the group;
- the group among the supplementary groups, from `SO_PEERGROUPS` (4.13+) or from
  `/proc/<pid>/status` when the peer shares the daemon's pid namespace.

The group is **3460** by default (outside Android's AID ranges); `daemon --gid N`
overrides it. On a plain Linux host the daemon uses a real `usb-arsenal` group if one
exists. Bionic has no group database, so on Android the number is what counts.

On a container client running a kernel older than 4.13, supplementary groups cannot be
seen across pid namespaces, so a non-root user there needs the group as their primary
gid.

## Images travel as open files

`storage start IMAGE` run through the daemon opens IMAGE on the client side, in the
client's own mount namespace and with the client's own permissions. It sends the fd
over the socket and swaps IMAGE for `--fd=3`. The daemon's command hands the kernel
`/proc/self/fd/3`, and the kernel follows the magic link to the real file wherever it
lives. No path ever has to mean the same thing on both sides. The SELinux relabel
(see [research/03](research/03-mass-storage-cdrom.md)) is done on the fd.

## A Droidspaces container

1. Bind-mount the host's `Run/` directory, not the socket file, onto the container's
   workspace. A bind-mounted socket file would keep pointing at the old inode after a
   daemon restart.
   ```
   -B /data/local/USB-Arsenal/Run:/var/lib/USB-Arsenal/Run
   ```
   or `bind_mounts=` in `container.config`. Restart the container afterwards.
2. For non-root users inside the container:
   ```sh
   groupadd -g 3460 usb-arsenal
   usermod -aG usb-arsenal <user>
   ```
3. Copy the binary in (`/data/local/USB-Arsenal/bin/usb-arsenal`) and run it as usual.
   `usb-arsenal version` should say `mode: daemon`.

HID nodes are created in the host's `/dev`. A container with hardware access (devtmpfs)
sees them, and `hid start --group` sets their owner by number, since the daemon cannot
resolve container group names.

## Gateway tethering: let OpenWrt serve the host

```sh
usb-arsenal tether start --gateway OpenWrt [--gateway-iface usb0]
```

This works like Droidspaces' gateway mode and VirtualAP's managed mode:
- `usb0` stays in the host namespace, on a bridge `ua-usb` that has no address (IPv6
  off, bridge netfilter off, MAC pinned).
- One veth runs from the bridge into the container. Its far end is created inside the
  container under its final name, so OpenWrt's netifd sees it appear and never sees a
  rename.
- USB Arsenal does no DHCP or NAT in this mode; the container does.
- The network side re-checks every 2 s:
  - If the container restarted (new network namespace), the cable is rebuilt.
  - If the gadget was rebound (M62-style kernels make a new `usb0`), `usb0` goes back
    on the bridge.
- `tether stop` removes the veth, then the bridge.

`--gateway NAME` finds the container through Droidspaces' `Pids/<name>.pid`, with
`droidspaces --name NAME pid` as fallback. `--gateway-pid PID` and
`--gateway-netns PATH` work for any other container runtime.

### OpenWrt side (once)

```sh
uci set network.usb=interface
uci set network.usb.device='usb0'
uci set network.usb.proto='static'
uci set network.usb.ipaddr='192.168.43.1'
uci set network.usb.netmask='255.255.255.0'
uci set network.usb.ip6assign='64'
uci set dhcp.usb=dhcp
uci set dhcp.usb.interface='usb'
uci set dhcp.usb.start='100'
uci set dhcp.usb.limit='150'
uci set dhcp.usb.leasetime='12h'
uci add_list firewall.@zone[0].network='usb'   # the lan zone
uci commit
/etc/init.d/network reload; /etc/init.d/dnsmasq reload; /etc/init.d/firewall reload
```

Android kernels usually lack nf_tables, so fw4 may not load. VirtualAP's
`provision_openwrt` falls back to
`iptables-legacy -t nat -A POSTROUTING -o eth0 -j MASQUERADE` for the same reason.

An existing VirtualAP setup can be reused as is: its `vaplan` interface already has
DHCP and a firewall zone, so `--gateway-iface vaplan0` works with no changes (while
VirtualAP itself is not running).

## The KernelSU module

`make module` builds every architecture into `output/usb-arsenal-<version>.zip`:
- `customize.sh` keeps the binary for the device ABI as `system/bin/usb-arsenal`, which
  puts it on the Android shell's PATH, and copies it to the workspace `bin/`.
- `service.sh` starts the daemon at boot.
- `uninstall.sh` gives the controller back and stops the daemon.

The daemon runs in KernelSU's `su` domain; there is no custom SELinux domain.

## Host side: interface names

A composite gadget makes the host name its network interface by interface number
(`enp0s20f0u9i3` instead of `enp0s20f0u9` on Linux). NetworkManager may then leave the
new interface `disconnected` until `nmcli device connect <iface>`, or a profile matching
it, activates it. Fixed MACs (from `ro.serialno`) keep it stable after that.

If the host should keep its own default route (for instance a workstation testing the
gateway mode), give the USB link a profile that never takes it:

```sh
nmcli connection add type ethernet con-name "USB Arsenal (no default route)" \
  match.interface-name "enp0s20f0u*" ipv4.never-default yes ipv6.never-default yes
```

## Verified (a166p, 5.15, Fedora host)

- Daemon on the Android side: socket and directory modes as above, a second instance
  refused, `version` reports daemon mode.
- Relayed `storage start` with fd passing: the host read the FAT16 image.
- Relayed NAT tether: the host got 192.168.42.130 and reached 1.1.1.1 through `wlan0`.
- Gateway into the OpenWrt container (`--gateway-iface vaplan0`):
  - The host got a lease from OpenWrt's dnsmasq, with its DNS and an IPv6 prefix, and
    reached the internet through OpenWrt.
  - A `storage start` rebind kept `usb0` on the bridge.
  - `stop` removed the links.
- Restarting the OpenWrt container: the cable was re-plugged into the new namespace by
  itself. The host's DNS through OpenWrt was back after about 13 s (graceful stop plus
  boot), with one more ~7 s blip while OpenWrt's services started, and the host kept
  its lease.
- From the Ubuntu container through the bind-mounted `Run/`:
  - As root: storage from a container path, HID, and NAT tether (the host's default
    route went through the phone).
  - As a member of group 3460: `status`, and storage of the user's own image.
  - A non-member was refused.
