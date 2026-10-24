# Testing

A gadget change that goes wrong can leave a phone with no USB, or panic its kernel. Test
the hand-back first, and keep the evidence.

## Without a phone

`sudo tests/dummy_hcd.sh` runs storage, a CD-ROM swap, HID, NCM, `status` and `stop`
against the kernel's `dummy_hcd` virtual controller, whose host side is the same PC. A
`dummy_udc.*` is never picked by itself (every GKI phone has one), so the script names it with
`--udc`; do the same when running commands against it by hand.

What needs root but no controller (`identity`, `setup`, the daemon and its relay, `hid write`
against a pty) runs without sudo in a user namespace, with a tmpfs over `/var/lib` so the
workspace is owned by the namespace's root:

```sh
unshare -rm sh -c 'mount -t tmpfs none /var/lib && output/usb-arsenal setup show'
```

The daemon needs `--gid 0` there, since gid 3460 is not mapped.

## On a phone

- **Run `adb devices` first.** If no line ends in `device`, stop.
- **ADB drops while the gadget holds the port,** so run each test as one background
  script and read its log afterwards:
  ```sh
  adb shell 'su -c "cd /data/local/tmp; nohup sh -c \"
    dmesg -c >/dev/null
    ./usb-arsenal storage start fat.img >> t.log 2>&1
    dmesg | grep -iE \\\"WARNING:|BUG|free_irq|already bound\\\" >> t.log
    ./usb-arsenal stop >> t.log 2>&1
  \" >/dev/null 2>&1 &"'
  ```
- **Check the kernel log after every step,** not only at the end. Warnings
  ("already-free IRQ", "duplicate filename") come before the panic they lead to.
- **Watch from the PC while the script runs:**
  - `lsusb` and `lsusb -v -d VID:PID` (the strings the device sends);
  - `lsblk -f` and `lsblk -S` (filesystem, SCSI inquiry);
  - `/proc/bus/input/devices` (HID);
  - `ip -4 addr` and a ping or `dig @192.168.42.1` (tether, gateway).
- **Keep the PC's own connection safe:** give the USB link a `never-default`
  NetworkManager profile (see [../tethering.md](../tethering.md)), or a gateway restart
  stalls the machine running the tests.

## After a panic

The phone reboots. Collect the log before anything else:

```sh
adb shell su -c "cat /proc/last_kmsg"                 # the whole previous boot
adb shell su -c "cat /sys/fs/pstore/dmesg-ramoops-0"  # the tail; may be cut short
```

Samsung's panic handlers print a long task dump that can push the oops out of pstore.
`/proc/last_kmsg` usually still has it. Look for the first `WARNING:` or `BUG` from
`usb-arsenal`, then read the kernel source at that call trace.

The oops also dumps the code bytes around the PC. **Decode those** (`llvm-mc
--disassemble` or by hand) before trusting a line number. The shipped kernel is built
with LTO, so a rebuilt object maps offsets only roughly. Mapping `process_rx_w+0x84`
from a non-LTO rebuild first pointed at the wrong line; the dumped bytes showed an
inlined `skb_dequeue()` and the real bug in `ncm_unwrap_ntb()`.

## Test devices so far

| Device | Kernel | Backend | Notes |
|---|---|---|---|
| Galaxy A16 5G (a166p) | 5.15 | own gadget | also tested from a Droidspaces container, with an OpenWrt gateway |
| Galaxy S10 ([M62-backport](https://github.com/ravindu644/M62-backport)) | 4.14 | own gadget | found the `soft_connect` panic and three f_ncm panics; NCM works with patch `0003` |
