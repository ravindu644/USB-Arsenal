# Tethering

`usb-arsenal tether start` gives the host a network adapter over the same cable as
everything else. There are two ways to serve it.

## NAT mode (default)

```sh
usb-arsenal tether start
usb-arsenal tether start --address 10.42.0.1/24 --dns 9.9.9.9 --upstream wlan0
```

- **The phone side is `192.168.42.129/24`** (`--address`). A built-in DHCP server offers
  the host the next address.
- **The host's traffic is NATed** out of whatever connection the phone is using. On
  Android that is the network netd marks as default; on Linux, the main routing table's
  default route. `--upstream` pins one interface.
- **When the phone moves between Wi-Fi and mobile data**, the route follows as soon as
  Android switches networks. If Android's network service restarts and flushes the
  firewall, the rules are put back within two seconds.
- **DNS** handed to the host is `1.1.1.1` and `8.8.8.8` unless `--dns` says otherwise.
- **IPv6 too, where the kernel can NAT it** (`CONFIG_IP6_NF_NAT`; `check` shows the
  "IPv6 NAT" line). The host gets an address in `fd75:7361::/64` by SLAAC and a default
  route, NATed out of the phone's IPv6 connection, as Droidspaces does for containers.
  Hosts still prefer IPv4 for public sites, since the prefix is a ULA. On an IPv6-only
  carrier, IPv6 is what carries the traffic. Without the nat table, the host gets IPv4
  only.
- **NCM is used where it works:** it has class drivers in Windows 11, macOS and Linux.
  RNDIS (`--rndis`) is for Windows 7 and 8, and `--ncm` overrides a profile that picks
  RNDIS. Both carry fixed MAC addresses, so Windows does not add a new adapter every time.
- **On Samsung 4.x kernels without the f_ncm fix,** USB Arsenal uses RNDIS automatically;
  see [kernel-patches/README.md](../kernel-patches/README.md).

The network side runs as a small background process. `tether stop` or `stop` ends it and
removes its firewall rules. Adding storage or HID while tethering keeps the link: on
kernels that re-create the interface at every re-plug, it is configured again by itself.

`--no-net` only adds the network function, with no address, DHCP or NAT, for setting it
up yourself. It forgets a saved gateway, and a gateway ignores `tether_net=0`.

## Gateway mode: let OpenWrt be the router

```sh
usb-arsenal tether start --gateway OpenWrt
usb-arsenal tether start --gateway OpenWrt --gateway-iface vaplan0
```

The USB link goes to a Droidspaces container (an OpenWrt one, typically), which then does
DHCP, DNS and firewalling for the host. USB Arsenal only plugs the cable:
- **`usb0` stays on the Android side**, on a bridge `ua-usb` with no address.
- **One virtual cable runs from that bridge into the container.** Its end appears inside
  the container as `usb0` (`--gateway-iface`).
- **If the container restarts, the cable is plugged into the new one by itself.** The host
  keeps its lease and is back after the container's boot time.
- **If the gadget re-plugs, `usb0` goes back on the bridge.**

`--gateway` finds a Droidspaces container by name. For other runtimes, use
`--gateway-pid PID` or `--gateway-netns /path/to/netns`.

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
uci set dhcp.usb.ra='server'          # IPv6: odhcpd announces the /64 from ip6assign
uci set dhcp.usb.dhcpv6='server'
uci set dhcp.usb.ra_default='1'       # default router even with only a ULA prefix
uci add_list firewall.@zone[0].network='usb'   # the lan zone
uci commit
/etc/init.d/network reload; /etc/init.d/dnsmasq reload; /etc/init.d/firewall reload
/etc/init.d/odhcpd reload                       # absent on images without IPv6
```

These are the settings VirtualAP's managed mode writes. The Android side has IPv6 off on
`usb0`, the bridge and the veth, so it never takes an address or a route from OpenWrt's
announcements.

Android kernels usually lack nf_tables, so fw4 may not load. If the host gets an address
but no internet, add NAT by hand:
`iptables-legacy -t nat -A POSTROUTING -o eth0 -j MASQUERADE`, and for IPv6
`ip6tables-legacy -t nat -A POSTROUTING -o eth0 -j MASQUERADE`.

**Already set up VirtualAP's managed mode?** Its `vaplan` interface has DHCP and a
firewall zone, so `--gateway-iface vaplan0` works with no OpenWrt changes, as long as
VirtualAP itself is not running.

## The host side

- **The adapter's name depends on what else is in the gadget.** On Linux it is
  `enp0s20f0u9` alone, and `enp0s20f0u9i3` next to storage and HID. NetworkManager
  sometimes leaves a newly named adapter disconnected; `nmcli device connect <name>`
  fixes it, and the name stays the same after that.
- **The host may start routing all its traffic through the phone,** because a wired link
  often wins over Wi-Fi. If the computer should keep its own connection (a workstation
  you are working on, say), give the link a profile that never takes the default route:
  ```sh
  nmcli connection add type ethernet con-name "USB Arsenal (no default route)" \
    match.interface-name "enp0s20f0u*" ipv4.never-default yes ipv6.never-default yes
  ```
