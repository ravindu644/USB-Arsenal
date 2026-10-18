# 04. Tethering

Code: `src/gadgets/tether.c`, with `src/net/` lifted from Droidspaces.

## Which function

- **NCM** is the default. It is built into every defconfig surveyed (including stock
  exynos and GKI 5.15/6.6), and Windows 11, macOS and Linux all have a class driver for
  it.
- **RNDIS** is the fallback, used when NCM is missing or `--rndis` is passed. It is
  gone from GKI after 5.10. Windows only loads its driver unprompted when the device
  carries Microsoft OS descriptors with compatible ID `RNDIS` / `5162001`, so
  `rndis_os_desc()` writes `os_desc/use`, `b_vendor_code=0xcd`, `qw_sign=MSFT100`, and
  links the config into `os_desc/`. This is skipped on the piggyback backend, where
  `g1`'s `os_desc` belongs to Android.
- The device class is Misc/IAD (0xEF/0x02/0x01) for every composite, which is what
  Windows wants for an NCM or RNDIS function inside one.
- MACs are fixed per device (FNV hash of `ro.serialno` or `/etc/machine-id`, locally
  administered). With random MACs Windows adds "Ethernet 7, 8, 9..." on every start.

## The network side (daemon)

After bind:
1. Read the function's `ifname` (old kernels report `(unnamed net_device)` until bound).
2. Bring the link up and add 192.168.42.129/24 (`--address`). The host is offered the
   next address by `ds_dhcp_server_start`, a single-lease DHCP server on an AF_PACKET
   socket bound to the interface.
3. Save `ip_forward`, then set it to 1.
4. NAT: `ds_ipt_ensure_masquerade(subnet)`, plus FORWARD and INPUT ACCEPT on the
   interface and an MSS clamp. These go through the raw ip_tables socket first and fall
   back to the iptables binary. Droidspaces' safety contract still holds: never flush,
   never change a policy, only insert rules scoped to our interface and subnet.
5. Uplink (`ua_find_uplink`): `--upstream` if given, else Android's netd
   default-network rule, else the main table's default route, else a name scan
   (`wlan*`, `eth*`, `v4-*`, `rmnet*`, ...).
6. Android only: `to <subnet> lookup main` (priority 6090) so replies find our connected
   route, and `from <subnet> lookup <uplink table>` (6100) so the host's traffic leaves
   through the uplink. Both are below Android's VPN rules (10000+). Plain Linux needs
   neither, because the main table already routes everything.

Every 2 s the daemon re-asserts `ip_forward` (Android resets it) and re-probes the
uplink. When the default network moves between Wi-Fi and mobile data, the FROM rule
moves with it.

On SIGTERM: stop DHCP, remove the MASQUERADE and ACCEPT rules and the policy rules.
Off Android, `ip_forward` goes back to its old value. On Android netd owns it, and
Android's own tethering may need it by then. The MSS clamp stays: it is not scoped to
us, and Droidspaces inserts the identical rule.

## Lessons from the NetHunter `usbtethering` script

The script (`ravindu644/Nethunter_APK_KernelSU@a488e77`, `NetHunter/assets/scripts/bin/usbtethering`)
does not build a gadget. It expects `rndis0` to exist. Its sequence (flush, add an
address, link up, add a route, `ip rule ... iif usb lookup <upstream>`, FORWARD and
MASQUERADE, `ip_forward=1`, ISC dhcpd inside a chroot) is the right shape. Three things
were not copied:
- The route goes into whatever table the *first* `ip rule` line names. On many Android
  versions that line is `local`. We find the uplink table from netd's rule instead.
- Teardown writes `ip_forward=0`, which also breaks Android's own tethering.
- It needs dhcpd from a Kali chroot. Ours is built in.

## Known limits

- The kernel creates `usb0` in the initial network namespace. A container with its own
  (Droidspaces NAT or gateway mode) cannot see it, so `tether start` refuses with that
  reason and removes the function it added. `--no-net` still works there, and the
  network side has to run in the host namespace.
- A composition change while tethering (for example `storage start` adding the drive)
  rebinds the gadget. If the kernel re-registers the netdev, the address and the DHCP
  socket go stale; `tether stop` + `tether start` fixes it. This is on the roadmap.
- IPv6: NAT66 after Droidspaces (ULA `fd75:7361::/64`, RAs from `ra.c`, ip6tables
  MASQUERADE when `ds_ipt6_available()`); IPv4 only on kernels without the nat table.
