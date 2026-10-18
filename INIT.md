# USB Arsenal: Project Brief

Read this once, fully, before you touch anything. It tells you *what* to build and
*where to look*. It deliberately does not tell you *how*, that is your job. Dig, research,
and design. When something feels off, go read the kernel source or search the web until it
does not.

Then read [AGENTS.md](./AGENTS.md) for the house rules (style, the laziness ladder,
commits, hard constraints).

---

## What USB Arsenal is

A root Android tool that turns one phone into a **composite USB gadget** over configfs:
several USB devices presented to a host PC at the same time, from a single cable.

The inspiration is DriveDroid (dead since ~2018, built on the old `android_usb` driver
that modern kernels replaced with configfs). USB Arsenal is not a DriveDroid port and needs
no decompiling of it, everything DriveDroid did lives in kernel source you already have.
USB Arsenal aims higher: DriveDroid's bootable storage *plus* tethering *plus* HID *plus*
(eventually) a webcam, all composable, to a quality bar set by Droidspaces.

The **core is C**. Its only job is to drive the kernel gadget subsystem: create gadget
functions, set their attributes, link them into a config, bind to the USB device
controller, raise the connection, and tear everything down cleanly. Everything that can
be the kernel's job *is* the kernel's job (see the laziness ladder in AGENTS.md, rung 3).

---

## The four goals

Build in this order. The gadget core comes before all of them, because every function
depends on it.

0. **Gadget core (the foundation).** Take the USB device controller away from Android's
   own gadget (`g1`), build USB Arsenal's gadget, add functions, bind, raise the D+ pullup,
   then tear down and hand the controller back so ADB/MTP return. This handoff with
   Android's init and USB HAL is the hardest and most dangerous part. Prove it with an
   empty gadget (no functions) before building any function on top. One interface that
   every function below calls: build, add-function, bind, bring-up, teardown, restore.

1. **Mass storage, DriveDroid-style.** Mount an ISO and boot it on a host PC.
   - The kernel function is `f_mass_storage`. A LUN has `file`, `ro`, `cdrom`,
     `removable`, `nofua` attributes. That is the whole mechanism.
   - The boot trick is the LUN's **mode**: `cdrom=1` makes the gadget a SCSI CD-ROM, so
     BIOS/UEFI boots a plain ISO9660/UDF image (Windows ISOs, non-hybrid Linux ISOs) via
     El Torito. `cdrom=0` presents a disk, which is how isohybrid Linux ISOs and
     pen-drive images boot. Decide the mode from the image (an MBR/GPT signature means it
     can boot as a disk; otherwise CD-ROM). Research how DriveDroid chose, and confirm
     against the kernel's CD-ROM emulation limits.
   - Also support plain writable pen-drive storage (a backing image or block device).
   - A Ventoy-style on-PC menu is possible but is a stretch goal, note it in the roadmap,
     do not build it first.

2. **USB tethering.** Give the host a network over USB.
   - The kernel functions are `f_ncm` (preferred: built into modern GKI, native Windows
     11 / macOS / Linux driver) and `f_rndis` (fallback for older kernels and older
     Windows; needs the Microsoft OS descriptors / IAD device class to auto-install).
     RNDIS was dropped from GKI after 5.10, so probe at runtime and default to NCM.
   - **Do not reinvent the host-networking side.** Droidspaces already solved DHCP, RA,
     iptables, netlink and the NAT/upstream plumbing. Read and port from
     `../Droidspaces-OSS/src/net/` (`dhcp.c`, `ra.c`, `iptables.c`, `netlink.c`,
     `network.c`). Adapt, credit, keep their choke-point discipline.
   - A known-good reference shell script for the whole USB-tethering bring-up:
     https://github.com/ravindu644/Nethunter_APK_KernelSU/raw/a488e77e175414eb45bdafc28fc0ba5e541d4746/NetHunter/assets/scripts/bin/usbtethering
     Study it for the exact sequence (interface up, addressing, forwarding, NAT), then do
     it properly in C.

3. **HID (gadgets only, nothing more).** Create the HID functions so `/dev/hidg0`
   (keyboard) and `/dev/hidg1` (mouse) appear. USB Arsenal's job ends there: it wires up the
   function and the device nodes, sets permissions so a userspace process can write
   reports, and stops. What anyone writes to those nodes is not USB Arsenal's concern, it is
   a generic HID interface.
   - The kernel function is `f_hid`: set `protocol`, `subclass`, `report_length`, and
     `report_desc` (the HID report descriptor) *before* bind. The node appears once the
     function is bound into the live gadget.
   - Report-descriptor examples (keyboard + mouse, boot protocol) are in
     `/tmp/report_desc.txt`. Copy them into the repo under `docs/references/` so they
     survive, then use them.

4. **UVC (webcam). TODO only.** The kernel function is `f_uvc`, driven through a userspace
   daemon that feeds frames (see the kernel's `uvc-gadget` sample). Leave a stub and a
   roadmap entry. Do not build it yet.

---

## Resources (dig through all of these)

- **Kernel sources: `../Kernels/`.** This is your primary reference, not the web. The
  gadget subsystem is `drivers/usb/gadget/`: `function/f_mass_storage.c`,
  `storage_common.c`, `function/f_hid.c`, `function/f_ncm.c`, `function/f_rndis.c`,
  `function/f_uvc.c`, `configfs.c`, and `udc/core.c`. Compare across the range you must
  support:
  - **3.18 floor:** `../Kernels/samsung_kernel_t385` (also `t295`, `a015f/g` for old).
  - **4.x / 5.x Samsung:** `exynos9820`, `exynos990`, `M62-backport` (4.14),
    `a165f` (5.10), `a166p` (5.15), `sm8550`.
  - **Latest GKI:** `google_kernel_pixel10pro-2026-01`, `p10-pro-krnl`.
  Check which `CONFIG_USB_CONFIGFS_*` each enables, how the LUN/HID/NCM attributes differ
  between versions, and whether configfs on that kernel is mainline or Samsung-patched.
- **Samsung gadget "duct-tapes": `docs/references/samsung-gadget-duct-tapes.md`.** Read
  this carefully, it is hard-won field knowledge about the real traps:
  - the hardcoded `android0` name that blocks a second gadget (and the patch that fixes
    it, already in `M62-backport`);
  - the `linked_func` vs `func_list` bug where a symlink alone does not bind under
    `CONFIG_USB_CONFIGFS_UEVENT` (also patched in `M62-backport`);
  - the Android **pullup HACK** in `udc/core.c`: `echo <udc> > UDC` binds but leaves D+
    down, so the host sees nothing until you poke `soft_connect` (or `enable` on the HAL
    path). This bites every kernel in the range.
  - the HAL fighting you via `sys.usb.config` triggers on stock kernels.
  Treat the doc as describing *stock* vs *patched* behaviour: design the core with two
  backends behind one interface (own separate gadget where the kernel allows it; piggyback
  `g1` via the `androidN` HAL attributes where it does not), and detect which at runtime.
- **Droidspaces networking: `../Droidspaces-OSS/src/net/`.** Port tethering plumbing from
  here. Also read `../Droidspaces-OSS/AGENTS.md` for the conventions USB Arsenal inherits.
- **USB tethering reference script:** the Nethunter `usbtethering` URL above.
- **HID report descriptors:** `/tmp/report_desc.txt` (copy into `docs/references/`).
- **Documentation pattern to copy: `../Dragon-Control-Center/docs/`** and its
  `ROADMAP.md`. Match that structure and that level of detail in your own `docs/`.
- **Prior art: tjado's USB Gadget Tool** (https://github.com/tejado/android-usb-gadget,
  GPL-3.0). An Android app that creates configfs gadgets (HID keyboard/mouse, FIDO CCID,
  UVC) and stops at enabling them, the same "wire up the gadget, userspace drives the
  node" boundary USB Arsenal uses for HID. Read it for how it handles configfs across
  kernels. Mind the GPL-3.0 licence: study it, do not copy code unless USB Arsenal's
  licence is compatible.

---

## How to work

- **Use the skills available to you** when they fit: reverse-engineering / kernel-source
  skills for digging the sources, a web-search skill when the kernel source leaves a
  question open (USB spec details, El Torito, Windows RNDIS driver quirks), a planning
  skill before a big subsystem. Pick what suits the task.
- **Research first, code second.** For each subsystem: read the kernel function's source
  across the version range, read the duct-tapes, confirm the sysfs/configfs interface,
  *then* design. The laziness ladder shortens the code you write, never the reading.
- **Capability detection over hardcoding.** The same binary runs on a 3.18 Samsung tablet
  and a 2026 Pixel. Probe `/sys/class/udc`, `/config/usb_gadget/*/functions`, the kernel
  version, and whether the configfs patches are present. Degrade gracefully, grey out what
  the kernel cannot do.
- **The teardown/restore path is sacred.** Build it in step 0, test it first, and make
  every error path route through it. A half-built gadget leaves the phone with no USB.
- **Leave the hardware knobs.** Real controllers, real hosts and picky firmware behave
  differently from the spec. Where a value needs tuning on real hardware (CD-ROM mode
  fallbacks, enumeration timing, pullup settling), leave the knob and a note, not a
  hardcoded guess.

---

## First moves (suggested, not prescribed)

1. Read this file, AGENTS.md, and the duct-tapes doc end to end.
2. Copy `/tmp/report_desc.txt` into `docs/references/hid-report-descriptors.txt`.
3. Survey `../Kernels/` for the gadget subsystem across the version range; write what you
   find into `docs/` (which kernels support what, mainline vs patched configfs, attribute
   differences).
4. Write `docs/ROADMAP.md` (model it on Dragon's): phase 0 gadget core, then storage,
   tethering, HID, with UVC as a later phase. This is how you track your own progress.
5. Design the gadget core's interface and the repo layout (one `.c` per subsystem, a
   `Makefile` with real targets, `.clangd`). Then start on phase 0.

The end state, in one breath: **a thing that can mount an ISO you can boot from, give the
host USB tethering, and wire up `/dev/hidg0` and `/dev/hidg1`, with UVC left as a TODO.**
