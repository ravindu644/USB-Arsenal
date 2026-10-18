# AGENTS.md

Instructions for AI coding agents working in this repository.

**New here? Read [INIT.md](./INIT.md) first.** It is the project brief: what USB Arsenal
is, the resources to dig through, and the direction each subsystem has to go. This file
is only the house rules.

## What this is

USB Arsenal is a root Android tool that builds a **composite USB gadget** through configfs,
so one phone can present itself to a host PC as several USB devices at once: a bootable
mass storage drive (mount an ISO, boot it), a USB network adapter (tethering), and HID
devices (`/dev/hidg0`, `/dev/hidg1` for userspace to drive). UVC (webcam) is a later TODO.

The core is written in **C**. It talks to the kernel's gadget subsystem, nothing more:
create functions, set attributes, link them into a config, bind to the UDC, bring the
link up, and tear it all down cleanly so the phone's own USB (ADB/MTP) comes back.

The architecture is not fixed yet. INIT.md gives direction, you design it.

## Build

`make native`, `make aarch64`, `make armhf`, `make x86_64`, `make x86`: static musl
builds into `output/usb-arsenal`. Toolchains are found as `<triplet>-gcc` in
`$MUSL_CROSS`, `~/toolchains/<triplet>-cross/bin`, `PATH` or `/opt/cross/bin`.
`make format` runs clang-format over `src/`. Bare `make` prints help.

**No wildcard SRCS**: add each new `.c` to the list in the Makefile. No
header-dependency tracking: `make clean` after editing a header. The editor gets its
flags from `compile_flags.txt` (clangd reads it directly).

Layout: `src/include/usbarsenal.h` is the only header a `.c` includes (plus
`src/net/net.h` for the networking pieces). `src/gadget.c` is the core every command
goes through (begin, add, commit, teardown). The commands live in `src/gadgets/`.
`src/net/` is lifted from Droidspaces; keep its `ds_` names so it can be re-synced.

End-to-end check without a phone: `sudo tests/dummy_hcd.sh` (dummy_hcd virtual UDC).

## Testing on a device

Run `adb devices` first, every time. If no line ends in `device`, stop and say so.
A gadget change that half-applies can leave the phone with no working USB, so always
have a teardown-and-restore path, and test it before testing anything else.

## Commits

- Run `make format` before committing any change to a `.c` or `.h` file.
- Sign off every commit: `git commit -s`.
- **Never add a `Co-Authored-By:` trailer for an AI agent.** Human co-authors are fine.
- Prefixes, matching the style Droidspaces uses: `fix:`, `refactor:`, `feat:`, `docs:`,
  or a subsystem name (`gadget:`, `storage:`, `net:`, `hid:`, `uvc:`). Use
  `fix(security):` for anything security related.

## Style

Four rules. They apply to code, comments, commit messages, and documentation.

**No em-dashes.** Use a comma, a full stop, or rewrite the sentence.

**No ASCII banner comments.** No rows of `-----`, no `=====`, no boxed section headers.
An SPDX licence block at the top of each file is fine.

**Comments sound like a person.** Say why the code does something, or what breaks if it
does not. Skip comments that restate the line below them.

```c
/* Bad: write 1 to the UDC */
echo_udc(udc);

/* Good: Android's bind HACK leaves D+ down, so assert the pullup ourselves after bind */
udc_soft_connect(udc);
```

**Ten lines that work beat a hundred that do the same thing.** Delete before you add.
A smaller diff in the right place is the goal, not a smaller diff anywhere.

## Before you write it at all

Work down this list and stop at the first answer that holds.

1. Does this need to exist? A flag nobody asked for, a knob for a value that never
   changes, an abstraction with one implementation: skip it and say so in one line.
2. Does it already exist here? Grep first.
3. **Does the kernel already do it?** This is the big one for USB Arsenal. The gadget
   subsystem, configfs, `f_mass_storage`, `f_hid`, `f_ncm`/`f_rndis`, `f_uvc` already
   implement the hard parts. Use the kernel's interface, do not reimplement the protocol
   in userspace unless a goal genuinely needs it. Research the kernel sources (see
   INIT.md) before writing a line.
4. Does an installed dependency / libc cover it? Never add a dependency for a few lines.
5. Can it be one line? Then it is one line.
6. Only then write the smallest thing that works.

Two answers work? Take the higher one and move on.

The list shortens the solution, never the reading. Trace the flow the change touches
before you pick a rung. A small diff in the wrong place is a second bug, not a lazy fix.

Never simplify away input validation at a trust boundary, a fail-closed check, error
handling that would leave the phone's USB broken, or anything the requester asked for by
name.

## Hard constraints

- **Kernel floor is 3.18** (see `../../Kernels/samsung_kernel_t385`). The top end is the
  latest GKI. The same core has to work across that whole range, so detect capabilities
  at runtime (what functions the kernel built, the UDC name, patched vs stock configfs),
  never hardcode a kernel-specific path. See the Samsung duct-tapes in
  `docs/references/samsung-gadget-duct-tapes.md`. Stock 3.18 ships no configfs gadget
  at all, so the real floor is "3.18 built with `CONFIG_USB_CONFIGFS`"; `check` refuses
  the rest with the reason (docs/research/01-kernel-survey.md).
- **Never hardcode the UDC name.** Read it from `/sys/class/udc`.
- **Teardown must always restore Android's own gadget.** A crash or error path that
  leaves the UDC unbound kills ADB and MTP. This is the one thing that must never break.
- Root is required. Fail closed and loud when a configfs path or permission is missing.

## Documentation

Every finding and design decision goes under `docs/`, the way Dragon-Control-Center does
it (`../Dragon-Control-Center/docs/`). Keep a `docs/ROADMAP.md` you tick off as you go,
modelled on `../Dragon-Control-Center/docs/ROADMAP.md`. Kernel research, edge cases, and
"why it works this way" notes go in `docs/` as you discover them, not in your head.
