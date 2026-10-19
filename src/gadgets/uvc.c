/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `uvc`: a USB webcam through f_uvc, streaming a built-in YUYV test pattern.
 * The kernel does the USB side; we write the descriptors and run the loop
 * AOSP's DeviceAsWebcam runs (UVCProvider.cpp): answer PROBE/COMMIT, queue
 * frames. See docs/developer/research/06-uvc.md and uvc-guide.md.
 */

#include "usbarsenal.h"
#include <linux/usb/ch9.h>
#include <linux/usb/g_uvc.h>
#include <linux/usb/video.h>
#include <linux/videodev2.h>
#include <sys/mman.h>

#define UVC "uvc.ua"
#define MAXPACKET 3072 /* 3 x 1024 a microframe, high-bandwidth isochronous */
#define NBUFS 4

/* musl's ioctl() takes an int request, and V4L2's do not fit one */
#define xioctl(fd, req, arg) ioctl(fd, (int)(req), arg)

/* Only what the pattern can draw: a host that picks a format we advertise
 * but never send shows a black camera. Intervals are in 100 ns units. */
struct frame {
  const char *name;
  unsigned w, h, interval;
};

static const struct frame frames[] = {
    {"480p", 640, 480, 333333},
    {"720p", 1280, 720, 1000000},
};
#define NFRAMES (sizeof(frames) / sizeof(frames[0]))

void ua_help_uvc(void) {
  printf("Usage: " UA_NAME " uvc <action>\n\n"
         "Add a USB webcam to the gadget. The host sees a standard UVC camera\n"
         "(no driver needed) showing a moving colour-bar test pattern.\n\n"
         "Actions:\n"
         "  start   Create the webcam and start streaming\n"
         "  stop    Remove it\n\n"
         "Formats: YUYV 640x480 at 30 fps, 1280x720 at 10 fps.\n\n"
         "The kernel keeps the whole gadget off the bus until the streaming\n"
         "process has the webcam open, so while the webcam is in the gadget\n"
         "that process must run; 'status' shows it.\n\n"
         "Examples:\n"
         "  " UA_NAME " uvc start\n"
         "  ffplay /dev/video0    # on a Linux host\n"
         "  " UA_NAME " uvc stop\n");
}

static void pid_path(char *out, size_t size) {
  ua_ws_path("Pids", "uvc.pid", out, size);
}

static void info_path(char *out, size_t size) {
  ua_ws_path("Gadget", "uvc.info", out, size);
}

static pid_t uvc_pid(void) {
  char p[PATH_MAX];
  pid_path(p, sizeof(p));
  return ua_read_our_pid(p);
}

/* Called before every unbind: f_uvc must never see its node still open
 * across one. 4.14's release path deactivates a function that is no longer
 * bound, and 5.15 waits up to 1.5 s in unbind for exactly this case. */
void ua_uvc_kill(void) {
  char p[PATH_MAX];
  pid_t pid = uvc_pid();
  if (pid > 0 && kill(pid, SIGTERM) == 0) {
    /* waitpid reaps it when it is our own child, which kill(pid, 0) would
     * otherwise see as a live zombie until the timeout */
    for (int i = 0;
         i < 50 && kill(pid, 0) == 0 && waitpid(pid, NULL, WNOHANG) != pid; i++)
      usleep(100000);
    if (kill(pid, 0) == 0) {
      ua_warn("UVC process %d did not exit, killing it", (int)pid);
      kill(pid, SIGKILL);
      waitpid(pid, NULL, 0);
    }
  }
  pid_path(p, sizeof(p));
  unlink(p);
  info_path(p, sizeof(p));
  unlink(p);
}

static int mk(const char *fn, const char *rel) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/%s", fn, rel);
  return mkdir(p, 0755) < 0 && errno != EEXIST ? -1 : 0;
}

/* ss class directories are missing on some kernels: optional */
static int ln(const char *fn, const char *target, const char *at, int opt) {
  char t[PATH_MAX], a[PATH_MAX];
  snprintf(t, sizeof(t), "%s/%s", fn, target);
  snprintf(a, sizeof(a), "%s/%s", fn, at);
  if (symlink(t, a) == 0 || errno == EEXIST || (opt && errno == ENOENT))
    return 0;
  return -1;
}

static int set(const char *fn, const char *dir, const char *attr,
               unsigned long long v) {
  char p[PATH_MAX], s[32];
  snprintf(p, sizeof(p), "%s/%s/%s", fn, dir, attr);
  snprintf(s, sizeof(s), "%llu\n", v);
  return write_file(p, s);
}

/* The layout of Documentation/usb/gadget-testing.rst. A format goes into the
 * streaming header before the header goes into a class: that link locks it. */
static int build(const char *fn) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/function_name", fn);
  if (write_file(p, UA_PROJECT_NAME) < 0 ||
      set(fn, ".", "streaming_maxpacket", MAXPACKET) < 0 ||
      mk(fn, "control/header/h") < 0 ||
      ln(fn, "control/header/h", "control/class/fs/h", 0) < 0 ||
      ln(fn, "control/header/h", "control/class/ss/h", 1) < 0 ||
      mk(fn, "streaming/uncompressed/u") < 0)
    return -1;
  /* YUY2 and 16 bits a pixel are the format's defaults */
  for (size_t i = 0; i < NFRAMES; i++) {
    const struct frame *f = &frames[i];
    unsigned long long size = (unsigned long long)f->w * f->h * 2;
    unsigned long long rate = size * 8 * 10000000ULL / f->interval;
    char d[64];
    snprintf(d, sizeof(d), "streaming/uncompressed/u/%s", f->name);
    if (mk(fn, d) < 0 || set(fn, d, "wWidth", f->w) < 0 ||
        set(fn, d, "wHeight", f->h) < 0 ||
        set(fn, d, "dwMaxVideoFrameBufferSize", size) < 0 ||
        set(fn, d, "dwMinBitRate", rate) < 0 ||
        set(fn, d, "dwMaxBitRate", rate) < 0 ||
        set(fn, d, "dwDefaultFrameInterval", f->interval) < 0 ||
        set(fn, d, "dwFrameInterval", f->interval) < 0)
      return -1;
  }
  return mk(fn, "streaming/header/h") < 0 ||
                 ln(fn, "streaming/uncompressed/u", "streaming/header/h/u", 0) <
                     0 ||
                 ln(fn, "streaming/header/h", "streaming/class/fs/h", 0) < 0 ||
                 ln(fn, "streaming/header/h", "streaming/class/hs/h", 0) < 0 ||
                 ln(fn, "streaming/header/h", "streaming/class/ss/h", 1) < 0
             ? -1
             : 0;
}

static int setup_fn(struct ua_gadget *g, int *created) {
  char fn[PATH_MAX], p[PATH_MAX + 32];
  ua_gadget_func_dir(g, UVC, fn, sizeof(fn));
  *created = access(fn, F_OK) != 0;
  if (ua_gadget_add(g, UVC, fn, sizeof(fn)) < 0)
    return -1;
  ua_gadget_unlink(g, UVC);
  /* Built by an earlier start: the linked headers lock their frames, which
   * hold the same values anyway */
  snprintf(p, sizeof(p), "%s/streaming/class/fs/h", fn);
  if (access(p, F_OK) == 0)
    return 0;
  if (build(fn) < 0) {
    ua_error("Cannot build the UVC descriptors in %s: %s", fn, strerror(errno));
    return -1;
  }
  return 0;
}

/* The streaming process */

struct cam {
  int fd;
  int set_cs; /* the selector whose SET_CUR data comes next, 0 = none */
  struct uvc_streaming_control probe, commit;
  void *buf[NBUFS];
  size_t len[NBUFS];
  int nbufs, streaming;
  unsigned seq;
  struct timespec next;
  char node[PATH_MAX];
};

static volatile sig_atomic_t stopping;

static void on_term(int sig) {
  (void)sig;
  stopping = 1;
}

/* 100%-ish colour bars in Y, U, V: white, yellow, cyan, green, magenta,
 * red, blue, black */
static const unsigned char bars[8][3] = {
    {235, 128, 128}, {210, 16, 146}, {170, 166, 16}, {145, 54, 34},
    {106, 202, 222}, {81, 90, 240},  {41, 240, 110}, {16, 128, 128},
};

/* YUYV packs two pixels in 4 bytes: Y0 U Y1 V. One row of bars, copied down,
 * and a white band sweeping down so a frozen stream is obvious. */
static void draw(unsigned char *p, const struct frame *f, unsigned seq) {
  size_t stride = (size_t)f->w * 2;
  for (unsigned x = 0; x < f->w; x += 2) {
    const unsigned char *b = bars[x * 8 / f->w];
    unsigned char *q = p + x * 2;
    q[0] = b[0], q[1] = b[1], q[2] = b[0], q[3] = b[2];
  }
  for (unsigned y = 1; y < f->h; y++)
    memcpy(p + y * stride, p, stride);
  unsigned band = (seq * 8) % f->h;
  for (unsigned y = band; y < band + 16 && y < f->h; y++)
    for (size_t x = 0; x < stride; x += 2)
      p[y * stride + x] = 235, p[y * stride + x + 1] = 128;
}

static void set_info(const struct cam *c) {
  char p[PATH_MAX];
  info_path(p, sizeof(p));
  int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0644);
  if (fd < 0)
    return;
  const struct frame *f = &frames[c->commit.bFrameIndex - 1];
  if (c->streaming)
    dprintf(fd, "%s, streaming %ux%u at %u fps", c->node, f->w, f->h,
            10000000 / f->interval);
  else
    dprintf(fd, "%s, idle", c->node);
  close(fd);
}

/* ponytail: the struct is little-endian on the wire and every target we
 * build for is little-endian, so no byte swapping */
static void fill_ctrl(struct uvc_streaming_control *s, unsigned frame) {
  const struct frame *f = &frames[frame - 1];
  memset(s, 0, sizeof(*s));
  s->bmHint = 1;
  s->bFormatIndex = 1;
  s->bFrameIndex = frame;
  s->dwFrameInterval = f->interval;
  s->dwMaxVideoFrameSize = f->w * f->h * 2;
  s->dwMaxPayloadTransferSize = MAXPACKET;
  s->bmFramingInfo = 3;
  s->bPreferedVersion = 1;
  s->bMaxVersion = 1;
}

/* Camera controls (the control interface) are stalled, as the kernel's
 * uvc-gadget sample does; only PROBE and COMMIT on the streaming interface
 * get answers. The kernel trims each answer to the host's wLength. */
static void on_setup(struct cam *c, const struct usb_ctrlrequest *req,
                     struct uvc_request_data *r) {
  unsigned cs = req->wValue >> 8;
  /* f_uvc rewrites the interface in wIndex to 0 (control) or 1 (streaming)
   * on 5.15 and later, so no configfs lookup: a streaming request has no
   * entity ID and a nonzero interface. ponytail: 5.10 passes the raw
   * numbers, so there, with another function before ours, the host's VC
   * error-code query reads as COMMIT GET_CUR; harmless, it gets 1 byte. */
  int streaming = (req->wIndex >> 8) == 0 && (req->wIndex & 0xff) != 0;
  struct uvc_streaming_control *s = (void *)r->data;

  r->length = -EL2HLT;
  if ((req->bRequestType & USB_TYPE_MASK) != USB_TYPE_CLASS || !streaming ||
      (cs != UVC_VS_PROBE_CONTROL && cs != UVC_VS_COMMIT_CONTROL))
    return;
  r->length = sizeof(*s);
  switch (req->bRequest) {
  case UVC_SET_CUR:
    c->set_cs = cs;
    break;
  case UVC_GET_CUR:
    memcpy(s, cs == UVC_VS_PROBE_CONTROL ? &c->probe : &c->commit, sizeof(*s));
    break;
  case UVC_GET_MIN:
  case UVC_GET_DEF:
    fill_ctrl(s, 1);
    break;
  case UVC_GET_MAX:
    fill_ctrl(s, NFRAMES);
    break;
  case UVC_GET_RES:
    memset(s, 0, sizeof(*s));
    break;
  case UVC_GET_LEN:
    r->data[0] = sizeof(*s);
    r->data[1] = 0;
    r->length = 2;
    break;
  case UVC_GET_INFO:
    r->data[0] = 0x03; /* supports GET and SET */
    r->length = 1;
    break;
  default:
    r->length = -EL2HLT;
  }
}

/* The host's choice for the SET_CUR just announced. Needs no response: the
 * kernel already finished the transfer. */
static void on_data(struct cam *c, const struct uvc_request_data *d) {
  struct uvc_streaming_control in;
  if (!c->set_cs)
    return;
  memset(&in, 0, sizeof(in));
  if (d->length > 0)
    memcpy(&in, d->data,
           (size_t)d->length < sizeof(in) ? (size_t)d->length : sizeof(in));
  unsigned frame = in.bFrameIndex < 1         ? 1
                   : in.bFrameIndex > NFRAMES ? NFRAMES
                                              : in.bFrameIndex;
  fill_ctrl(c->set_cs == UVC_VS_PROBE_CONTROL ? &c->probe : &c->commit, frame);
  c->set_cs = 0;
}

static void stream_off(struct cam *c) {
  int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
  struct v4l2_requestbuffers rb;
  if (c->streaming)
    xioctl(c->fd, VIDIOC_STREAMOFF, &type);
  for (int i = 0; i < c->nbufs; i++)
    munmap(c->buf[i], c->len[i]);
  if (c->nbufs) {
    memset(&rb, 0, sizeof(rb));
    rb.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    rb.memory = V4L2_MEMORY_MMAP;
    xioctl(c->fd, VIDIOC_REQBUFS, &rb);
  }
  if (c->streaming)
    ua_log("Stream stopped");
  c->nbufs = c->streaming = 0;
  set_info(c);
}

static int stream_on(struct cam *c) {
  const struct frame *f = &frames[c->commit.bFrameIndex - 1];
  unsigned size = f->w * f->h * 2;
  struct v4l2_format fmt;
  struct v4l2_requestbuffers rb;
  int type = V4L2_BUF_TYPE_VIDEO_OUTPUT;

  memset(&fmt, 0, sizeof(fmt));
  fmt.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
  fmt.fmt.pix.width = f->w;
  fmt.fmt.pix.height = f->h;
  fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
  fmt.fmt.pix.field = V4L2_FIELD_NONE;
  fmt.fmt.pix.sizeimage = size;
  memset(&rb, 0, sizeof(rb));
  rb.count = NBUFS;
  rb.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
  rb.memory = V4L2_MEMORY_MMAP;
  if (xioctl(c->fd, VIDIOC_S_FMT, &fmt) < 0 ||
      xioctl(c->fd, VIDIOC_REQBUFS, &rb) < 0)
    goto fail;
  for (unsigned i = 0; i < rb.count && i < NBUFS; i++) {
    struct v4l2_buffer b;
    memset(&b, 0, sizeof(b));
    b.index = i;
    b.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    b.memory = V4L2_MEMORY_MMAP;
    if (xioctl(c->fd, VIDIOC_QUERYBUF, &b) < 0 || b.length < size)
      goto fail;
    c->buf[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, c->fd,
                     b.m.offset);
    if (c->buf[i] == MAP_FAILED)
      goto fail;
    c->len[i] = b.length;
    c->nbufs++;
  }
  if (xioctl(c->fd, VIDIOC_STREAMON, &type) < 0)
    goto fail;
  c->streaming = 1;
  /* After STREAMON, as DeviceAsWebcam does: since 5.15 f_uvc starts its
   * pump only for a buffer queued while streaming, so buffers queued
   * before would sit there and the host would get empty packets forever */
  for (int i = 0; i < c->nbufs; i++) {
    struct v4l2_buffer b;
    memset(&b, 0, sizeof(b));
    b.index = (unsigned)i;
    b.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
    b.memory = V4L2_MEMORY_MMAP;
    b.bytesused = size;
    draw(c->buf[i], f, c->seq++);
    if (xioctl(c->fd, VIDIOC_QBUF, &b) < 0)
      goto fail;
  }
  clock_gettime(CLOCK_MONOTONIC, &c->next);
  ua_log("Streaming %ux%u at %u fps", f->w, f->h, 10000000 / f->interval);
  set_info(c);
  return 0;
fail:
  ua_error("Cannot start the stream: %s", strerror(errno));
  stream_off(c);
  return -1;
}

/* The host's pull and the bus pace the stream; this keeps it from running
 * faster than the committed interval when there is bandwidth to spare */
static void pace(struct cam *c, unsigned interval) {
  c->next.tv_nsec += (long)interval * 100;
  while (c->next.tv_nsec >= 1000000000L) {
    c->next.tv_nsec -= 1000000000L;
    c->next.tv_sec++;
  }
  clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &c->next, NULL);
  clock_gettime(CLOCK_MONOTONIC, &c->next);
}

static void pump(struct cam *c) {
  const struct frame *f = &frames[c->commit.bFrameIndex - 1];
  struct v4l2_buffer b;
  memset(&b, 0, sizeof(b));
  b.type = V4L2_BUF_TYPE_VIDEO_OUTPUT;
  b.memory = V4L2_MEMORY_MMAP;
  if (xioctl(c->fd, VIDIOC_DQBUF, &b) < 0 || b.index >= (unsigned)c->nbufs)
    return;
  pace(c, f->interval);
  draw(c->buf[b.index], f, c->seq++);
  b.bytesused = f->w * f->h * 2;
  if (xioctl(c->fd, VIDIOC_QBUF, &b) < 0)
    ua_warn("QBUF: %s", strerror(errno));
}

static void on_event(struct cam *c) {
  struct v4l2_event ev;
  struct uvc_event *ue = (void *)&ev.u.data;
  struct uvc_request_data r;
  if (xioctl(c->fd, VIDIOC_DQEVENT, &ev) < 0)
    return;
  switch (ev.type) {
  case UVC_EVENT_SETUP:
    memset(&r, 0, sizeof(r));
    on_setup(c, &ue->req, &r);
    ua_debug("SETUP %02x %02x %04x %04x %u -> %d", ue->req.bRequestType,
             ue->req.bRequest, ue->req.wValue, ue->req.wIndex, ue->req.wLength,
             r.length);
    if (xioctl(c->fd, UVCIOC_SEND_RESPONSE, &r) < 0)
      ua_warn("UVCIOC_SEND_RESPONSE: %s", strerror(errno));
    break;
  case UVC_EVENT_DATA:
    on_data(c, &ue->data);
    break;
  case UVC_EVENT_STREAMON:
    stream_on(c);
    break;
  case UVC_EVENT_STREAMOFF:
  case UVC_EVENT_DISCONNECT:
    stream_off(c);
    break;
  }
}

/* The way DeviceAsWebcam finds it: the output node whose driver is g_uvc,
 * which skips the phone's own cameras and codecs. It appears a moment after
 * bind, once ueventd or devtmpfs has made it. */
static int open_node(char *node, size_t size) {
  for (int tries = 0; tries < 30; tries++) {
    DIR *d = opendir("/dev");
    struct dirent *e;
    while (d && (e = readdir(d))) {
      struct v4l2_capability cap;
      if (strncmp(e->d_name, "video", 5) != 0)
        continue;
      snprintf(node, size, "/dev/%s", e->d_name);
      int fd = open(node, O_RDWR | O_NONBLOCK | O_CLOEXEC);
      if (fd < 0)
        continue;
      memset(&cap, 0, sizeof(cap));
      if (xioctl(fd, VIDIOC_QUERYCAP, &cap) == 0 &&
          !strcmp((char *)cap.driver, "g_uvc") &&
          (cap.capabilities & V4L2_CAP_VIDEO_OUTPUT)) {
        closedir(d);
        return fd;
      }
      close(fd);
    }
    if (d)
      closedir(d);
    usleep(100000);
  }
  return -1;
}

static int serve(int ready) {
  /* SETUP last: subscribing to it is what raises the pullup on 5.15+ */
  static const unsigned evs[] = {UVC_EVENT_DATA, UVC_EVENT_STREAMON,
                                 UVC_EVENT_STREAMOFF, UVC_EVENT_DISCONNECT,
                                 UVC_EVENT_SETUP};
  struct sigaction sa;
  struct cam c;
  memset(&c, 0, sizeof(c));
  memset(&sa, 0, sizeof(sa));
  sa.sa_handler = on_term; /* no SA_RESTART: poll() must return */
  sigaction(SIGTERM, &sa, NULL);
  sigaction(SIGINT, &sa, NULL);

  if ((c.fd = open_node(c.node, sizeof(c.node))) < 0) {
    ua_error("No g_uvc video node appeared after the bind");
    return -1;
  }
  fill_ctrl(&c.probe, 1);
  c.commit = c.probe;
  for (size_t i = 0; i < sizeof(evs) / sizeof(evs[0]); i++) {
    struct v4l2_event_subscription sub;
    memset(&sub, 0, sizeof(sub));
    sub.type = evs[i];
    if (xioctl(c.fd, VIDIOC_SUBSCRIBE_EVENT, &sub) < 0) {
      ua_error("Cannot subscribe to UVC events on %s: %s", c.node,
               strerror(errno));
      close(c.fd);
      return -1;
    }
  }
  char ok = '1';
  if (write(ready, &ok, 1) < 0)
    ua_debug("ready pipe: %s", strerror(errno));
  close(ready);
  set_info(&c);
  ua_log("Webcam on %s", c.node);

  while (!stopping) {
    struct pollfd p = {c.fd, POLLPRI | (c.streaming ? POLLOUT : 0), 0};
    if (poll(&p, 1, -1) < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    if (p.revents & POLLPRI)
      on_event(&c);
    else if (p.revents & (POLLERR | POLLHUP)) {
      /* The queue failed under a stream (host gone), or the node itself */
      if (!c.streaming)
        break;
      stream_off(&c);
      continue;
    }
    if (p.revents & POLLOUT)
      pump(&c);
  }
  /* No STREAMOFF here: on 5.15 it completes a host alt-setting change,
   * and with none pending it warns. Closing makes f_uvc stop the stream
   * and free the buffers itself. */
  close(c.fd);
  return 0;
}

/* Called right after every bind, before the pullup: f_uvc binds
 * deactivated, so until this process subscribes the host sees nothing of
 * the gadget at all, storage and HID included. */
void ua_uvc_reapply(const struct ua_gadget *g) {
  char log[PATH_MAX];
  int pfd[2];
  if (!ua_gadget_has(g, UVC) || pipe2(pfd, O_CLOEXEC) < 0)
    return;
  ua_ws_path("Logs", "uvc.log", log, sizeof(log));
  pid_t pid = fork();
  if (pid < 0) {
    close(pfd[0]);
    close(pfd[1]);
    return;
  }
  if (pid == 0) {
    char p[PATH_MAX];
    FILE *f;
    /* Not the image `storage start` passed in, or whatever else the re-plug
     * that spawned us had open: this process outlives that command */
    for (int fd = 3; fd < 1024; fd++)
      if (fd != pfd[1])
        close(fd);
    ua_crit_child();
    setsid();
    if (chdir("/") < 0)
      _exit(1);
    int out = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    int null = open("/dev/null", O_RDONLY);
    if (null >= 0)
      dup2(null, STDIN_FILENO);
    if (out >= 0) {
      dup2(out, STDOUT_FILENO);
      dup2(out, STDERR_FILENO);
    }
    pid_path(p, sizeof(p));
    if ((f = fopen(p, "we"))) {
      fprintf(f, "%d\n", (int)getpid());
      fclose(f);
    }
    int ret = serve(pfd[1]);
    unlink(p);
    info_path(p, sizeof(p));
    unlink(p);
    _exit(ret == 0 ? 0 : 1);
  }
  close(pfd[1]);
  char c = '0';
  ssize_t r;
  while ((r = read(pfd[0], &c, 1)) < 0 && errno == EINTR)
    ;
  close(pfd[0]);
  if (r != 1 || c != '1')
    ua_error("The webcam's streaming process did not start (see %s). The "
             "host sees nothing of the gadget without it.",
             log);
}

static int do_start(void) {
  struct ua_gadget g;
  char p[PATH_MAX], info[PATH_MAX];
  int created = 0;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_UVC) < 0)
    return UA_EXIT_FAIL;

  if (!(ua_gadget_has(&g, UVC) && ua_gadget_live(&g) && uvc_pid() > 0)) {
    if (ua_gadget_begin(&g) < 0)
      return UA_EXIT_FAIL;
    if (setup_fn(&g, &created) < 0) {
      if (created)
        ua_gadget_remove(&g, UVC);
      ua_gadget_commit(&g);
      return UA_EXIT_FAIL;
    }
    /* The commit starts the streaming process */
    if (ua_gadget_commit(&g) < 0)
      return UA_EXIT_FAIL;
    if (uvc_pid() <= 0) {
      /* A webcam nobody serves keeps the whole gadget off the bus */
      if (ua_gadget_begin(&g) == 0) {
        ua_gadget_remove(&g, UVC);
        ua_gadget_commit(&g);
      }
      return UA_EXIT_FAIL;
    }
  }
  info_path(p, sizeof(p));
  if (read_file(p, info, sizeof(info)) > 0)
    ua_log("Webcam: %s", info);
  return UA_EXIT_OK;
}

static int do_stop(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, UVC)) {
    ua_uvc_kill();
    ua_log("UVC is not running");
    return UA_EXIT_OK;
  }
  /* begin stops the streaming process before it unbinds */
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, UVC);
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_log("Webcam removed");
  return UA_EXIT_OK;
}

void ua_status_uvc(const struct ua_gadget *g) {
  char p[PATH_MAX], info[PATH_MAX];
  if (!ua_gadget_has(g, UVC))
    return;
  info_path(p, sizeof(p));
  if (uvc_pid() > 0 && read_file(p, info, sizeof(info)) > 0)
    printf("UVC:        %s\n", info);
  else
    printf("UVC:        no streaming process (the host sees nothing)\n");
}

int ua_cmd_uvc(int argc, char **argv) {
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_uvc();
    return UA_EXIT_OK;
  }
  if (argc != 2) {
    ua_usage_error("uvc", "Too many arguments");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(argv[1], "start"))
    return do_start();
  if (!strcmp(argv[1], "stop"))
    return do_stop();
  if (argv[1][0] == '-')
    ua_usage_error("uvc", "Bad option '%s'", argv[1]);
  else
    ua_usage_error("uvc", "Unknown action '%s'", argv[1]);
  return UA_EXIT_USAGE;
}
