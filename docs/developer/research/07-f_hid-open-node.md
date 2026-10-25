# 07. f_hid and an open node across an unbind

Written 2026-10-25 at commit `c929762`. A Galaxy A13 (Exynos 850, 4.19.198) and a Pixel 5
(4.19) panicked when HID was switched off after the cable had been pulled while the app was
moving the mouse. Switching HID off with the cable still in never panicked.

## The panic

From the A13's `last_kmsg`, a few ms after the suspend freezer started:

```
list_del corruption. prev->next should be ffffff800d34bcd8, but was dead000000000100
kernel BUG at lib/list_debug.c:53!
Process dd (pid: 12689)
  __list_del_entry_valid+0x88/0x9c
  prepare_to_wait_event+0x118/0x138
  f_hidg_write+0xdc/0x33c
  vfs_write / ksys_write / el0_svc_compat
```

`dd` was the app of that day writing mouse reports. The wait entry on its stack (`x22`) points
both ways at `ffffffc839d8f460`, `hidg->write_queue`'s list head; the dump at `x19` shows that
head holding `dead000000000100 / dead000000000200`, the list poison values. The queue `dd` slept on
had been freed and its memory reused by an object whose own list was deleted. The freezer's
fake signal woke `dd`, and `prepare_to_wait_event` unlinked its entry from the dead head.

## Why: drivers/usb/gadget/function/f_hid.c (4.19.198)

1. Unplug: `hidg_disable` frees the IN request, sets `write_pending = 1` and `req = NULL`.
   Only `hidg_set_alt` (the next enumeration) clears it.
2. The next write sleeps in `wait_event_interruptible_exclusive(hidg->write_queue, ...)`
   forever.
3. HID off: `config_usb_cfg_unlink` → `usb_put_function` → `hidg_free` → `kfree(hidg)`.
   `hidg_unbind` only calls `cdev_del`, which does nothing to an open file. Nothing counts
   the open files.
4. The sleeper wakes into freed memory.

With the cable in, every report completes, so nobody sleeps when the function is freed.

A second way in, on every kernel: `hidg_bind` runs `init_waitqueue_head` and `cdev_init` on
the same `hidg` every bind. An unbind and a bind with HID kept (a storage or tether apply)
resets the queue under a sleeper, so its `list_del` finds a head that no longer points at it.

## Upstream

| Fix | Mainline | Stable |
|---|---|---|
| `89ff3dfac604` f_hidg lifetime vs cdev, `70a3288a7586` refcount leak (CVE-2022-50568) | 6.2 | 4.19.270, 5.10, 5.15; never 4.14 or 4.9 |
| `4e0a88254ad5` list and spinlock inits move from bind to alloc | 7.0 | 5.10.y to 6.12.y |
| `81ebd43cc0d6` no `cdev_init` while the cdev is in use, `2c863dbbeac7` its error code | after 7.0 | 6.12.y only |
| `937a8a3a8d46` wake readers on disable/unbind | 6.16 | none |

No local tree has the 2026 fixes, 5.15 and 6.6 included. `kernel-patches/` carries the
backports.

## The M62 (4.14.356): same bug, hidden

Samsung's 4.14 f_hid returns `-ESHUTDOWN` at the top of `f_hidg_write` once `hidg->req` is
NULL, so after an unplug the old `dd` failed and exited instead of sleeping. The read path has
no such check. The probe: `hid start`, a `cat /dev/hidg1` reader (a host never sends a mouse
an output report, so it sleeps on `read_queue`), `hid stop`, `hid start`, `kill -9` the reader.

- Unpinned it survived: the new `hidg` landed elsewhere and the old head still pointed at the
  entry, so `list_debug` saw nothing.
- Pinned to CPU 0 (`taskset -p 1`), so that SLUB's per-CPU freelist hands the freed slot to
  the next allocation, it panicked a second after the kill:
  `list_del corruption. prev->next should be ..., but was (null)` in `f_hidg_read`.

Whether a kernel without the fixes panics depends on whether the memory is reused, and it
only BUGs with `CONFIG_DEBUG_LIST` (both phones have it). Without it the list is silently
corrupted.

## The userspace answer

No process may hold one of our `/dev/hidgN` when our gadget unbinds. `ua_hid_evict`
(`src/gadgets/hid.c`) runs right after each unbind, in `ua_gadget_begin` and
`ua_gadget_teardown`. Every unlink (the free) and every bind (the reset) in our code comes after
one of those. Once the node is deleted nobody can open it again, so one look is enough. It reads each
of our functions' `dev` and walks `/proc` once as Droidspaces does. For each
`/proc/<pid>/fd` entry it does a `readlinkat`, and runs `fstatat`, which follows the magic link
to the inode so a deleted node still matches, only when the link names `/dev/` or a hidg node.
`fstatat` on a FUSE file asks its daemon, which can hang inside the critical section. It sends
`SIGKILL` to each holder (a caught signal restarts the syscall onto the queue), closes our own
inherited fds on the node, and waits for those pids only. Logs are `[DEBUG]`.

It fails closed. If `/proc` cannot be read or a holder outlives SIGKILL by a second, `begin`
leaves the gadget unbound and fails, since both a bind and an unlink would panic. `teardown`
still hands USB back but leaves our functions linked for a later `stop` to remove.

The `/proc/<pid>/fd` of the thread group leader is all it reads. A holder whose main thread
has exited while another thread sleeps on the node is missed.

Other ways out, and why not:
- Keeping the function linked while held: unlinking is the free, and a linked HID is still a
  keyboard on the host.
- Waking the sleeper with a caught signal or the freezer: the syscall restarts with the same
  `private_data`.
- Refusing while held: `stop` is the recovery path and must always hand USB back.
- Detecting a fixed kernel: vendors backport without a version change, and both fixes must be
  there.

Cost, measured on the M62 (792 processes, 16.2k fds): one single-threaded pass is 52 to
80 ms when the scheduler is free to pick a core and about 250 ms on a little core. Eight
threads only bring it to 35 to 45 ms, so it stays single-threaded. With the evicting binary
the pinned probe, a `storage start` rebind with a reader asleep, and a plain `hid stop` all
survive.

The app needs nothing: `HidWriter` starts a new `hid write` a second after one dies, and its
first report releases every key. A copy run in a container with its own pid namespace cannot
see holders outside it.
