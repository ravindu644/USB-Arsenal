/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "usbarsenal.h"

#include <fcntl.h>
#include <linux/videodev2.h>

#include "timer.h"
#include "video-buffers.h"
#include "video-source.h"

struct webcam_source {
  struct video_source source;
  char *path;
  unsigned char *frame;
  unsigned char *scratch;
  unsigned frame_size;
  struct timer *timer;
  bool streaming;
};

static struct webcam_source *to_webcam_source(struct video_source *source) {
  return (struct webcam_source *)((char *)source -
                                  offsetof(struct webcam_source, source));
}

static int read_frame(struct webcam_source *source, unsigned limit) {
  unsigned char header[2], tail[2];
  struct stat st;
  int fd = open(source->path, O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return -1;
  if (fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || st.st_size < 4 ||
      (uint64_t)st.st_size > limit || st.st_size > UA_WEBCAM_MAX_JPEG_BYTES ||
      pread(fd, header, sizeof(header), 0) != sizeof(header) ||
      pread(fd, tail, sizeof(tail), st.st_size - 2) != sizeof(tail) ||
      header[0] != 0xff || header[1] != 0xd8 || tail[0] != 0xff ||
      tail[1] != 0xd9) {
    close(fd);
    return -1;
  }

  size_t size = (size_t)st.st_size;
  size_t offset = 0;
  while (offset < size) {
    ssize_t n = pread(fd, source->scratch + offset, size - offset, offset);
    if (n <= 0) {
      close(fd);
      return -1;
    }
    offset += (size_t)n;
  }
  close(fd);
  memcpy(source->frame, source->scratch, size);
  source->frame_size = (unsigned)size;
  return 0;
}

static void webcam_source_destroy(struct video_source *video) {
  struct webcam_source *source = to_webcam_source(video);
  if (source->timer)
    timer_destroy(source->timer);
  free(source->frame);
  free(source->scratch);
  free(source->path);
  free(source);
}

static int webcam_source_set_format(struct video_source *source
                                    __attribute__((unused)),
                                    struct v4l2_pix_format *format) {
  return format->pixelformat == V4L2_PIX_FMT_MJPEG ? 0 : -EINVAL;
}

static int webcam_source_set_frame_rate(struct video_source *video,
                                        unsigned int fps) {
  if (!fps)
    return -EINVAL;
  timer_set_fps(to_webcam_source(video)->timer, (int)fps);
  return 0;
}

static int webcam_source_free_buffers(struct video_source *source
                                      __attribute__((unused))) {
  return 0;
}

static int webcam_source_stream_on(struct video_source *video) {
  struct webcam_source *source = to_webcam_source(video);
  int ret = timer_arm(source->timer);
  if (ret < 0)
    return ret;
  source->streaming = true;
  return 0;
}

static int webcam_source_stream_off(struct video_source *video) {
  struct webcam_source *source = to_webcam_source(video);
  source->streaming = false;
  return timer_disarm(source->timer);
}

static void webcam_source_fill_buffer(struct video_source *video,
                                      struct video_buffer *buffer) {
  struct webcam_source *source = to_webcam_source(video);
  if (read_frame(source, buffer->size) < 0 && !source->frame_size) {
    buffer->bytesused = 0;
    buffer->error = true;
    if (source->streaming)
      timer_wait(source->timer);
    return;
  }
  if (source->frame_size > buffer->size) {
    buffer->bytesused = 0;
    buffer->error = true;
    if (source->streaming)
      timer_wait(source->timer);
    return;
  }
  memcpy(buffer->mem, source->frame, source->frame_size);
  buffer->bytesused = source->frame_size;
  buffer->error = false;
  if (source->streaming)
    timer_wait(source->timer);
}

static const struct video_source_ops webcam_source_ops = {
    .destroy = webcam_source_destroy,
    .set_format = webcam_source_set_format,
    .set_frame_rate = webcam_source_set_frame_rate,
    .alloc_buffers = NULL,
    .export_buffers = NULL,
    .free_buffers = webcam_source_free_buffers,
    .stream_on = webcam_source_stream_on,
    .stream_off = webcam_source_stream_off,
    .queue_buffer = NULL,
    .fill_buffer = webcam_source_fill_buffer,
};

struct video_source *jpg_video_source_create(const char *path) {
  if (!path)
    return NULL;
  struct webcam_source *source = calloc(1, sizeof(*source));
  if (!source)
    return NULL;
  source->path = strdup(path);
  source->frame = malloc(UA_WEBCAM_MAX_JPEG_BYTES);
  source->scratch = malloc(UA_WEBCAM_MAX_JPEG_BYTES);
  source->timer = timer_new();
  source->source.ops = &webcam_source_ops;
  source->source.type = VIDEO_SOURCE_STATIC;
  if (!source->path || !source->frame || !source->scratch || !source->timer ||
      read_frame(source, UA_WEBCAM_MAX_JPEG_BYTES) < 0) {
    webcam_source_destroy(&source->source);
    return NULL;
  }
  return &source->source;
}

void jpg_video_source_init(struct video_source *source, struct events *events) {
  source->events = events;
}
