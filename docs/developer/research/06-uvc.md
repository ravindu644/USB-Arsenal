# 06. UVC: the phone as a USB webcam

Research of 2026-10-19, for the `uvc` command (ROADMAP Phase 4).

## What the kernel already does

`f_uvc` (`CONFIG_USB_CONFIGFS_F_UVC`) is the whole USB side: descriptors, the control and
streaming interfaces, isochronous transfers. Android 14 and later GKI kernels enable it by
default; the Galaxy A16 5G (a166p, 5.15) has `CONFIG_USB_F_UVC=y`,
`CONFIG_USB_CONFIGFS_F_UVC=y` and `CONFIG_VIDEO_DEV=y` on its stock kernel. Once bound,
the function appears as a V4L2 **output** node, `/dev/videoN`, and userspace has two jobs:
answer the host's UVC class requests, and queue frames.

## How Android does it: DeviceAsWebcam

AOSP's webcam mode is open source:
[platform/packages/services/DeviceAsWebcam](https://android.googlesource.com/platform/packages/services/DeviceAsWebcam)
(Apache-2.0, read at `aa3833e`, 2025-03). Its split:

- **Vendor init writes the descriptors**, not the app. The documented example
  ([source.android.com: Use a device as a webcam](https://source.android.com/docs/core/camera/webcam))
  creates `functions/uvc.0`, sets `function_name` and `streaming_maxpacket 3072`, makes
  `streaming/mjpeg/m/1080p` with `wWidth`, `wHeight`, `dwMaxVideoFrameBufferSize`,
  `dwDefaultFrameInterval` and `dwFrameInterval`, and links the control and streaming
  headers into the class directories. The Gadget HAL then links `uvc.0` like any other
  function (`GadgetFunction.UVC` since Android 14).
- **The service reads the formats back from the kernel**, not from configfs:
  `UVCProvider.cpp` globs `/dev/video*`, keeps the node whose `VIDIOC_QUERYCAP` says
  video output, and walks `VIDIOC_ENUM_FMT`, `ENUM_FRAMESIZES` and `ENUM_FRAMEINTERVALS`.
- **The event loop** (`UVCProvider.cpp`, about 950 lines): subscribe to
  `UVC_EVENT_SETUP`, `DATA`, `STREAMON`, `STREAMOFF` and `DISCONNECT`; on each
  `VIDIOC_DQEVENT` answer through `UVCIOC_SEND_RESPONSE`.
  - Class requests on the control interface are only acknowledged (no camera controls;
    their TODO b/267794640).
  - On the streaming interface, PROBE and COMMIT: `SET_CUR` stores the host's choice,
    `GET_CUR` returns it, `GET_MIN`/`GET_DEF` give format 1 frame 1, `GET_MAX` the last,
    `GET_LEN` 0x30 (48 bytes, UVC 1.5), `GET_INFO` 0x3. The chosen frame interval is the
    first advertised one at least as long as the requested one.
  - `STREAMON`: `VIDIOC_S_FMT` with the committed format, `REQBUFS` (MMAP), `QUERYBUF`
    and mmap each, `STREAMON`, then fill and `QBUF` buffers as frames arrive and `DQBUF`
    them back. `STREAMOFF` and `DISCONNECT` undo it, and a node that disappears without
    a `DISCONNECT` stops the service too.
- **Frames:** Camera2 delivers YUV, `Encoder.cpp` converts with libyuv (rotation, I420 to
  YUY2) or compresses with libjpeg for MJPEG.

## Constraints that carry over

- **Formats:** MJPEG and uncompressed YUYV are what hosts handle everywhere.
- **Bandwidth on USB 2.0** (about 60 MB/s at best, isochronous less): YUYV, at 2 bytes a
  pixel, tops out at 720p30, realistically 640x480 at 30 fps; MJPEG (about 1:10) reaches
  1080p and beyond. `streaming_maxpacket 3072` is the high-bandwidth isochronous maximum
  (3 x 1024 per microframe).
- **macOS:** Apple's UVC driver fails with these gadgets over USB 3 cables, per the AOSP
  page.

## H.264: frame-based formats

`f_uvc` never looks at a frame; it sends whatever userspace queues, which is why MJPEG
already works. H.264 only needs the kernel to *advertise* it, as a UVC 1.5 frame-based
format (`UVC_VS_FORMAT_FRAME_BASED`, 0x10), and only configfs writes descriptors.

| Kernel | `streaming/framebased/` |
|---|---|
| mainline 6.13 and later | yes: `7b5a58952fc3` "usb: gadget: uvc: configfs: Add frame-based frame format support" (2024-09) |
| Android 6.6 GKI (Pixel 10 tree) | yes, backported (`uvc_configfs.c`, `struct uvc_format_framebased` in `include/uapi/linux/usb/video.h`) |
| a166p 5.15, a165f 5.10, sm8550 5.15, Exynos 9820 4.14 | no |

With it, an app encodes with MediaCodec and queues one access unit per UVC frame: 4K30
H.264 is about 20 to 50 Mbit/s, far inside USB 2.0's isochronous budget (about 24 MB/s).
Linux `uvcvideo` and Windows 10+ decode it; macOS support is uncertain, so MJPEG stays
the format every host takes. Older kernels need the 6.13 commit backported (it is
self-contained: the configfs group, the descriptor struct, and copying it into the
function's descriptors). Without a kernel change the only route is a UVC function
written in userspace over FunctionFS, which reimplements `f_uvc`; not pursued.

Apps like Iriun reach 4K30 over a USB 2.0 cable differently: the phone encodes H.264 and
sends it over ADB forwarding or Wi-Fi to a client on the PC, which presents a virtual
webcam (v4l2loopback on Linux). The phone shows no video interface at all (seen on the
S10: MTP, CDC and ADB only). Quality comes from the codec, at the cost of a PC-side app;
UVC needs nothing on the host.

## What USB Arsenal would take

The descriptor half is ours, since there is no vendor init behind us: `uvc start` builds
`functions/uvc.ua` the way the example does, with a small fixed format list (YUYV 640x480,
MJPEG 640x480 / 1280x720 / 1920x1080, 30 fps, plus H.264 frame-based sizes up to 4K30
when the kernel has `streaming/framebased/`), and links it into the composite next to
storage, HID and tether. The streaming half is DeviceAsWebcam's loop, rewritten in C:
find the output node by `QUERYCAP`, answer PROBE/COMMIT as above, map buffers, queue
frames. Frames come from outside: a built-in test pattern (YUYV, no encoder needed)
first, then a future app, which owns the camera and the MJPEG encoding and hands frames
to `usb-arsenal uvc feed` over its stdin or a shared-memory descriptor through the
daemon.

The step-by-step plan for writing it is [uvc-guide.md](../uvc-guide.md).

## Found while writing it (2026-10-19)

Read in the 4.14 (M62), 5.15 (a166p) and 6.6 (Pixel GKI) trees, then seen on the a166p.

- **f_uvc binds deactivated** (`func.bind_deactivated = true` in `uvc_alloc()`). The
  pullup goes up only when userspace opens the node (4.14) or subscribes to
  `UVC_EVENT_SETUP` (5.15+); closing it deactivates the function again. Until then the
  host sees nothing of the gadget, storage and HID included. So `uvc` starts its process
  right after the UDC write and before USB Arsenal's own pullup. A gadget with `uvc` and no
  process is invisible.
- **The node must not stay open across an unbind.** On 4.14, `uvc_v4l2_release()` calls
  `uvc_function_disconnect()` on whatever the function is by then. 5.15 waits up to 500
  and then 1000 ms in `uvc_function_unbind()` for userspace to close, for this reason.
  USB Arsenal stops the process before every unbind (`ua_gadget_begin()`, teardown) and
  starts it again after every bind, which DeviceAsWebcam does not need because nothing
  re-plugs its gadget.
- **Interface numbers in events.** 5.15 and later rewrite `wIndex` before queueing a
  SETUP event: 0 for the control interface, 1 for streaming, whatever the composite's
  numbering. 4.14 passes the raw numbers and has no `bInterfaceNumber` attributes either.
  A streaming request is therefore one with no entity ID and a nonzero interface.
- **DATA events need no response.** The kernel has finished the OUT transfer by the time
  `UVC_EVENT_DATA` arrives (the kernel's uvc-gadget sample answers only SETUP).
- **MediaTek mtu3 stalls every SET_CUR.** It acks the SETUP at once, and when the OUT data
  arrives before a request is queued, `ep0_rx_state()` sets SENDSTALL, calling it a gadget
  driver bug. f_uvc queues the request from userspace, so the host's PROBE SET_CUR always
  fails (`Failed to set UVC probe control : -32 (exp. 34)`). GET requests work, because
  mtu3 waits in `ep0_queue()` for the IN direction. Mainline has the same code. The a166p
  enumerated as "USB Arsenal" and answered every GET, but no stream could start. A first
  patch held the data packet with RXPKTRDY set; an ftrace of `mtu3_handle_setup` and
  `mtu3_log` showed it cannot work: the controller has already acked the packet, the host
  goes to the status stage, and 48 µs later mtu3 is back in SETUP (SETUPEND). Our late
  response was then sent as the data of the host's next GET_CUR, and the real answer to
  that hit TX-END (EINVAL). A second mtu3 patch deferred acking the SETUP; the trace
  showed the data packet arriving anyway, and `ep0_rx_state()` stalling it. So mtu3
  cannot be made to wait. The fix moved to f_uvc: `kernel-patches/{5.10,5.15,6.6}/0003`
  queues the data stage of a control-OUT request in `uvc_function_setup()`, as every
  other function does, before the SETUP event goes out; userspace's later response to
  that SETUP is swallowed (counted, so responses stay matched). Google's 6.6 f_uvc has
  the same code as 5.15 here; Pixels work because dwc3 NAKs until a request is queued.
- **Queue buffers after STREAMON.** With `0003` the host committed and set alt 1, the
  endpoint ran, and 37,000 isochronous requests went out in 25 s, every one empty. Since
  5.15 (the `req_ready` rework), f_uvc's pump runs only for a buffer queued while
  streaming (`uvc_v4l2_qbuf()`) or after a completion took a ready request;
  `uvcg_video_enable()` only primes zero-length requests. Buffers queued before
  `VIDIOC_STREAMON`, as the kernel's uvc-gadget sample does, are never encoded.
  DeviceAsWebcam queues after STREAMON, and so does `uvc` now. Verified on the a166p:
  60 frames at 640x480 / 30 fps and 10 at 1280x720 / 10 fps, colour bars intact, and 30
  more after an `hid` re-plug.
- **No STREAMOFF on exit.** On 5.15 `VIDIOC_STREAMOFF` completes the host's alt-setting
  change (`uvc_function_setup_continue()`); with none pending it warns
  `usb_composite_setup_continue: Unexpected call`. On SIGTERM the process only closes the
  node; f_uvc's release stops the stream and frees the buffers.
- `CONFIG_USB_CONFIGFS_F_UVC` is off in the S10's 4.14 defconfig, and turning it on needs
  `kernel-patches/4.14/0007` (Samsung's vb2 and configfs signatures) to build.
- **S10 (dwc3, 4.14), first run: the phone hung on stream stop.** Fedora enumerated the
  camera, listed both YUYV sizes, and PROBE/COMMIT went through (no `-32`, unlike mtu3).
  Our process logged "Streaming 640x480 at 30 fps", but the host got no frames in 15 s.
  When ffmpeg gave up and the host set alt 0 (STREAMOFF), the phone stopped responding
  and rebooted by itself; the host saw a disconnect a second later, and the test script
  never ran its next step. Samsung's reset log records no kernel panic for it. The kernel log was lost: the next boots hit an unrelated KernelSU-Next soft
  lockup (`ksud` in `handle_sepolicy` holding SELinux's policy lock), which overwrote
  `last_kmsg` and pstore. Suspect: 4.14's `uvcg_video_enable(0)`, which dequeues each
  isochronous request and frees all of them at once; dwc3 4.14 has a dequeue path
  (`!r->trb`) that returns 0 without giving the request back. Not confirmed. Do not run
  `uvc` on 4.14 again without a live kernel log (ADB over Wi-Fi, `dmesg -w`).
- **So `uvc` needs the newer f_uvc.** Every tree read whose f_uvc has the
  `function_name` attribute also waits in `uvc_function_unbind()` for userspace
  (`func_connected_queue`): a165f 5.10, a166p 5.15, sm8550 5.15, Pixel 6.6. Both 4.x
  trees (M62 4.14, Exynos 990 4.19) have neither. `ua_uvc_safe()` probes for
  `function_name`; `check` shows uvc as refused and `uvc start` fails closed without
  that attribute. Verified on the S10: refused, the port was never taken, ADB stayed.
