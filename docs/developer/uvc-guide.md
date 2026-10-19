# Implementing `uvc`: a guide

How to add a USB webcam to USB Arsenal, step by step. The findings behind it are in
[research/06-uvc.md](research/06-uvc.md). Two references are worth keeping open while
writing it:
- AOSP's DeviceAsWebcam, `interface/jni/UVCProvider.cpp`
  ([source](https://android.googlesource.com/platform/packages/services/DeviceAsWebcam)),
  for the request handling and the buffer flow.
- The kernel's `uvc-gadget` userspace sample and `Documentation/usb/gadget-testing.rst`
  (the UVC section), for the configfs layout.

## Scope of the first version

`uvc start` / `uvc stop`, streaming a built-in YUYV test pattern. No app, no encoder,
no camera controls.

- **Formats:** YUYV only, 640x480 at 30 fps and 1280x720 at 10 fps. Offer only what the
  pattern can produce: a host that picks an advertised MJPEG format and gets nothing
  shows a black or frozen camera.
- **Later steps** (not in the first version): `uvc feed` taking frames from an app
  (MJPEG, and H.264 where the kernel has `streaming/framebased/`), then the app.

## Files

| File | Change |
|---|---|
| `src/gadgets/uvc.c` | new: help, `start`, `stop`, status, the descriptor builder and the streaming process |
| `Makefile` | add `gadgets/uvc.c` to `SRCS` (no wildcards) |
| `src/include/usbarsenal.h` | `UA_NEED_UVC`, and `ua_cmd_uvc`, `ua_help_uvc`, `ua_status_uvc`, `ua_uvc_kill` |
| `src/main.c` | a `uvc` entry in the command table; `ua_status_uvc()` in `status` |
| `src/check.c` | a `uvc` line under FUNCTIONS (`ua_probe_function(g, "uvc", NULL)`), the same key in `check --json` including the skipped branch, and `UA_NEED_UVC` in `ua_check_requirements()`; the H.264 line (`streaming/framebased`) waits for `uvc feed` |
| `src/gadget.c` | call `ua_uvc_kill()` in `ua_gadget_teardown()` next to `ua_tether_kill()`; and the recursive remover below |
| docs | a `uvc` section in usage.md, ROADMAP ticks and a status line, README only after hardware |

`hid.c` is the closest model for the command itself (options, `do_start`, `do_stop`,
status), and `tether.c` for the background process (`spawn_daemon()`, the pid file,
`ua_read_our_pid()`, the kill helper).

## 1. Removing a nested function

`ua_gadget_remove()` used to remove one level of subdirectories, which is enough for
`lun.N` but not for UVC, whose groups nest several levels deep and are tied together by
symlinks (class to header, header to format). A linked group refuses `rmdir`. Make the
remover generic:

1. unlink every symlink anywhere in the function's tree;
2. remove directories deepest first;
3. ignore failures on the kernel's default groups (`lun.0`, `os_desc`, uvc's `control`
   and `streaming`); they go with the function.

This only removes groups inside our own function, so it stays within the AGENTS.md rule
about never removing a gadget directory. Run `tests/dummy_hcd.sh` after changing it:
storage's LUN handling goes through the same function.

## 2. The descriptors

Create the function with `ua_gadget_add(g, "uvc.ua", ...)`, then build its tree before
`ua_gadget_commit()`. In shell terms, relative to `functions/uvc.ua`:

```sh
echo "USB Arsenal" > function_name        # missing on older kernels: skip quietly
echo 3072 > streaming_maxpacket           # 3 x 1024: high-bandwidth isochronous on USB 2.0

mkdir control/header/h
ln -s control/header/h control/class/fs/h
ln -s control/header/h control/class/ss/h # no ss class on some kernels: optional

mkdir streaming/uncompressed/u            # YUY2 and 16 bits per pixel are the defaults
mkdir streaming/uncompressed/u/480p
echo 640     > streaming/uncompressed/u/480p/wWidth
echo 480     > streaming/uncompressed/u/480p/wHeight
echo 614400  > streaming/uncompressed/u/480p/dwMaxVideoFrameBufferSize   # w * h * 2
echo 147456000 > streaming/uncompressed/u/480p/dwMinBitRate              # size * 8 * fps
echo 147456000 > streaming/uncompressed/u/480p/dwMaxBitRate
echo 333333  > streaming/uncompressed/u/480p/dwDefaultFrameInterval      # 100 ns units
echo 333333  > streaming/uncompressed/u/480p/dwFrameInterval
# same for 720p: 1280x720, 1843200 bytes, interval 1000000 (10 fps)

mkdir streaming/header/h
ln -s streaming/uncompressed/u streaming/header/h/u
ln -s streaming/header/h streaming/class/fs/h
ln -s streaming/header/h streaming/class/hs/h
ln -s streaming/header/h streaming/class/ss/h   # optional, as above
```

Order matters: a format must be linked into the streaming header before the header is
linked into a class directory, because that link locks the header. Do all of it while
the function is out of the config, like `hid.c`'s `setup_fn()`.

## 3. Finding the node

After the bind, `f_uvc` registers a V4L2 device. Walk `/dev/video*`, open each with
`O_RDWR | O_NONBLOCK`, and keep the one where `VIDIOC_QUERYCAP` reports `driver` as
`g_uvc` (and `V4L2_CAP_VIDEO_OUTPUT`). Checking the driver name skips the phone's own
camera nodes and codec (memory-to-memory) nodes, which can also report video output.

To tell control requests from streaming requests, no interface numbers are needed:
5.15 and later rewrite `wIndex` in the event to 0 (control) or 1 (streaming), and 4.14
has no `bInterfaceNumber` attributes. A streaming request has no entity ID (high byte 0)
and a nonzero interface. See research/06.

## 4. The event loop

Subscribe with `VIDIOC_SUBSCRIBE_EVENT` to `UVC_EVENT_SETUP`, `UVC_EVENT_DATA`,
`UVC_EVENT_STREAMON` and `UVC_EVENT_STREAMOFF` (from `linux/usb/g_uvc.h`). Then `poll()`
the node: `POLLPRI` means an event (`VIDIOC_DQEVENT`), `POLLOUT` a buffer to refill.

Every SETUP event gets an answer through `UVCIOC_SEND_RESPONSE` with a
`struct uvc_request_data`. A negative `length` stalls the request, which is the right
answer to anything unsupported. DATA events get none: the transfer is already over.
Subscribe to SETUP last, because on 5.15+ that subscription raises the pullup.

**SETUP on the control interface** (camera controls): stall, `length = -EL2HLT`, as the
kernel sample does. (DeviceAsWebcam acknowledges them instead; either works with
common hosts.)

**SETUP on the streaming interface**, control selector `wValue >> 8` either
`UVC_VS_PROBE_CONTROL` or `UVC_VS_COMMIT_CONTROL`:

| bRequest | Answer |
|---|---|
| `UVC_SET_CUR` | remember which selector the host is about to send; length = the size of `struct uvc_streaming_control` (the data arrives as a DATA event) |
| `UVC_GET_CUR` | the stored probe or commit structure |
| `UVC_GET_MIN`, `UVC_GET_DEF` | a filled structure for format 1, frame 1 |
| `UVC_GET_MAX` | a filled structure for the last frame |
| `UVC_GET_RES` | zeros |
| `UVC_GET_LEN` | 2 bytes: the structure size (0x22 for 34 bytes, or 0x30 for UVC 1.5's 48) |
| `UVC_GET_INFO` | 1 byte, `0x03` (supports GET and SET) |

Never answer more than the request's `wLength`.

**Filling the structure:** `bmHint` 1, `bFormatIndex`, `bFrameIndex`, `dwFrameInterval`
from the frame table, `dwMaxVideoFrameSize` w * h * 2, `dwMaxPayloadTransferSize` 3072,
`bmFramingInfo` 3, `bPreferedVersion` and `bMaxVersion` 1.

**DATA:** the host's `struct uvc_streaming_control` for the SET_CUR just announced.
Clamp `bFormatIndex` and `bFrameIndex` to what exists (both start at 1), take the
frame's interval, and store the result as the new probe or commit.

**STREAMON:**
1. `VIDIOC_S_FMT`, type `V4L2_BUF_TYPE_VIDEO_OUTPUT`, the committed width and height,
   `V4L2_PIX_FMT_YUYV`, `sizeimage` w * h * 2;
2. `VIDIOC_REQBUFS`, 4 buffers, `V4L2_MEMORY_MMAP`; `VIDIOC_QUERYBUF` and `mmap` each;
3. `VIDIOC_STREAMON`;
4. then fill every buffer with a frame and `VIDIOC_QBUF` it, setting `bytesused`. Not
   before STREAMON: since 5.15 f_uvc never encodes buffers queued earlier (research/06).

**POLLOUT while streaming:** `VIDIOC_DQBUF`, draw the next frame into that buffer,
`VIDIOC_QBUF` it back. The host's pull and the bandwidth pace the stream; sleep to the
committed interval between frames if it runs faster.

**STREAMOFF:** `VIDIOC_STREAMOFF`, `munmap` every buffer, `VIDIOC_REQBUFS` with a count
of 0. On exit (SIGTERM) only close the node: a STREAMOFF without a host alt-setting
change warns on 5.15.

## 5. The test pattern

YUYV packs two pixels into 4 bytes: Y0, U, Y1, V. Draw eight vertical colour bars, a
white stripe that moves a few pixels each frame, and a small frame counter, so a stall
or a dropped stream is visible at a glance. Compute one row of bars once per frame and
copy it down; 640x480 at 30 fps is about 18 MB/s of memory writes, which is nothing.

## 6. Lifecycle

- f_uvc binds deactivated: the host sees nothing of the gadget until the process has
  subscribed. So `ua_gadget_commit()` starts it (`ua_uvc_reapply()`) right after the UDC
  write and before `pullup()`, as `tether` does it: `setsid`, output to `Logs/uvc.log`,
  pid in `Pids/uvc.pid`, node and format in `Gadget/uvc.info` for `status`. The child
  calls `ua_crit_child()` first so it does not inherit the state lock, and closes the
  fds the re-plugging command had open (a storage image).
- The node must not stay open across an unbind (research/06), so the process does not
  try to survive a re-plug: `ua_gadget_begin()` and teardown stop it
  (`ua_uvc_kill()`) before unbinding, and the next commit starts a new one.
  `ua_read_our_pid()` makes sure a reused pid never gets the signal.
- Through the daemon, the process is forked from a session and survives it after
  `setsid`, like the tether process.

## 7. Testing

1. `tests/dummy_hcd.sh` cannot stream: dummy_hcd has no isochronous transfers, and
   Fedora's kernel has no `usb_f_uvc`. It still covers the recursive remover.
2. A phone to a Linux PC: `ffplay /dev/videoN` or any camera app shows the pattern.
   `ffmpeg -f v4l2 -input_format yuyv422 -video_size 640x480 -i /dev/videoN
   -frames:v 60 out.mkv` should give 60 frames. Start `hid` while it streams: the stream must come back after the re-plug. Check
   `dmesg` on the phone after each step, and end with `stop` and ADB back.
3. A Windows PC: the Camera app lists "USB Arsenal" and shows the pattern.

## Afterwards

- `uvc feed`: frames from outside over stdin or a shared-memory descriptor passed
  through the daemon (the way `storage start` passes image files), with MJPEG frame
  entries added to the descriptors.
- H.264 as a frame-based format where `check` finds `streaming/framebased/`; see
  research/06 for which kernels have it and the upstream commit to backport.
