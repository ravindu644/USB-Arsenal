# 05. Getting the tether interface into the tool's network namespace

Research of 2026-10-18, kernels in `~/Documents/Kernels` (a166p paths are under
`kernel-5.15/`). The problem: run from a container with its own network namespace
(Droidspaces NAT or gateway mode), `tether start` cannot see `usb0`.

## Facts

- **usb0 is always born in init_net.** `gether_setup_name_default()` calls
  `alloc_etherdev` with no `dev_net_set` (a166p `u_ether.c:825-866`, also t385 :1838,
  M62 :1106, p10 :819). Which netns the writer of the configfs files is in plays no part.
- **Registered once per function instance,** at the first bind
  (`if (!ncm_opts->bound)`, a166p `f_ncm.c:1460-1467`; rndis :704-709). It survives UDC
  unbind/rebind and is unregistered only when the function directory is removed
  (`gether_cleanup`, `f_ncm.c:1622-1633`).
  - **Exception: M62 4.14 with `CONFIG_USB_CONFIGFS_UEVENT`.** `ncm_unbind` frees it
    (`f_ncm.c:1833-1853`) and rndis reallocates it (`f_rndis.c:1040-1060`), so every
    rebind makes a fresh usb0 in init_net.
- **Nothing blocks a move.** There is no `NETIF_F_NETNS_LOCAL` and no `rtnl_link_ops` in
  u_ether on any version, so `ip link set usb0 netns X` works.
- **The move must be asked from usb0's current netns.** `rtnl_setlink` looks the device
  up in `sock_net(skb->sk)` (5.15 `rtnetlink.c:3107`), then checks CAP_NET_ADMIN over
  the target (`rtnl_link_get_net_capable`, :2349). It is the same on 3.18 (:1510-1521).
  `IFLA_TARGET_NETNSID` does not change where the device is looked up.
- **Container exit is safe.** `default_device_exit` moves a device without
  `rtnl_link_ops` back to init_net (a166p `dev.c:11558-11590`) instead of deleting it.
  Removing the function while usb0 is in another netns is also fine.

## Getting a handle on init_net from inside the container

| Way | Works |
|---|---|
| `/proc/<host pid>/ns/net` | No: host pids are invisible from a separate pid ns |
| `/proc/1/ns/net` when the pid ns is shared (chroot, Droidspaces host-net mode) | Yes |
| `IFLA_NET_NS_PID`, `pidfd_open` | No: pids resolve in the caller's pid ns |
| `RTM_GETNSID`, nsfs ioctls | No: ids or user namespaces, never a net ns fd |
| An fd passed over a filesystem-path unix socket | Yes |
| Droidspaces socketd | No: abstract socket (per netns), no netns opcodes |
| A host netns file bind-mounted into the container | Yes, but it gives container root all of host networking |

## Approaches, ranked

1. **Droidspaces moves it (host side).** The monitor already runs in init_net, already
   opens the container netns fd, and already re-checks every heartbeat
   (`ds_net_macvlan_wire`). A config option such as `usb_net_passthrough=usb0,ncm0,rndis0`
   would make the heartbeat move any matching link in with one RTM_NEWLINK carrying
   `IFLA_NET_NS_FD`. The heartbeat also catches M62's re-registration and a container
   restart. Least privilege; nothing new is exposed to the container.
2. **USB Arsenal moves it, given a host netns handle.** Open `/proc/self/ns/net` as
   `self`, `setns()` into the host netns, open a netlink socket, send RTM_NEWLINK
   (`ifi_index` = usb0, `IFLA_NET_NS_FD` = `self`), then `setns(self)` back. This is the
   pattern of `ds_netns_link_up` in Droidspaces `network.c:206-248`, and `ds_nl_rename`
   in our `netlink.c` is the request template. The handle comes from `/proc/1/ns/net` in
   a chroot, or from an opt-in bind mount (e.g. `/run/host-netns`) in a container. It
   must be redone after every rebind on M62-style kernels.
3. Do all of the networking from init_net (setns for the whole daemon). Not
   recommended: NAT would land in Android's tables, next to netd, and state would leak
   out of the container.

## What each setup needs

- **Plain host (Android or Linux):** nothing. usb0 is already in the tool's netns.
- **Chroot sharing the pid ns:** approach 2 with `/proc/1/ns/net`, entirely inside USB
  Arsenal.
- **Droidspaces with its own pid and net ns:** approach 1 in Droidspaces. USB Arsenal
  waits a few seconds for the link to appear, then fails with "enable Droidspaces USB
  passthrough".
- **docker / lxc on a board:** the host moves it
  (`ip link set usb0 netns $(docker inspect -f '{{.State.Pid}}' c)`, or LXC
  `lxc.net.N.type=phys`, `lxc.net.N.link=usb0`). USB Arsenal prints that command.

Moving usb0 into the container means the container's own uplink (for Droidspaces
gateway mode, the OpenWrt container) is what the host gets NATed through.
