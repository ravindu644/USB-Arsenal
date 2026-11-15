/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "usbarsenal.h"

#define FUNC UA_F_WEBCAM
#define MAX_JPEG_BYTES UA_WEBCAM_MAX_JPEG_BYTES
#define MAX_FRAME_WIDTH 1920u
#define MAX_FRAME_HEIGHT 1440u

int ua_uvc_gadget_main(int argc, char **argv);

static void pid_path(char *out, size_t size) {
  ua_ws_path("Pids", "webcam.pid", out, size);
}

static pid_t worker_pid(void) {
  char p[PATH_MAX];
  pid_path(p, sizeof(p));
  return ua_read_our_pid(p);
}

int ua_webcam_kill(void) {
  char p[PATH_MAX];
  pid_t pid = worker_pid();
  if (pid > 0) {
    if (kill(pid, SIGTERM) < 0 && errno != ESRCH) {
      ua_error("Cannot stop webcam feeder %d: %s", (int)pid, strerror(errno));
      return -1;
    }
    for (int i = 0; i < 30 && worker_pid() == pid; i++)
      usleep(100000);
    if (worker_pid() == pid) {
      ua_warn("Webcam feeder %d did not exit, killing it", (int)pid);
      if (kill(pid, SIGKILL) < 0 && errno != ESRCH) {
        ua_error("Cannot kill webcam feeder %d: %s", (int)pid, strerror(errno));
        return -1;
      }
      for (int i = 0; i < 10 && worker_pid() == pid; i++)
        usleep(100000);
      if (worker_pid() == pid) {
        ua_error("Webcam feeder %d is still running", (int)pid);
        return -1;
      }
    }
  }
  pid_path(p, sizeof(p));
  unlink(p);
  return 0;
}

static int image_dimensions(int fd, unsigned *width, unsigned *height) {
  unsigned char marker[2], segment[2];
  off_t pos = 2;
  for (;;) {
    if (pread(fd, marker, 2, pos) != 2 || marker[0] != 0xff)
      return -1;
    pos += 2;
    while (marker[1] == 0xff) {
      if (pread(fd, marker + 1, 1, pos++) != 1)
        return -1;
    }
    unsigned char code = marker[1];
    if (code == 0xd8 || code == 0xd9 || (code >= 0xd0 && code <= 0xd7) ||
        code == 0x01)
      continue;
    if (pread(fd, segment, 2, pos) != 2)
      return -1;
    unsigned length = (unsigned)segment[0] << 8 | segment[1];
    if (length < 2)
      return -1;
    if (code == 0xc0 || code == 0xc1 || code == 0xc2 || code == 0xc3 ||
        code == 0xc5 || code == 0xc6 || code == 0xc7 || code == 0xc9 ||
        code == 0xca || code == 0xcb || code == 0xcd || code == 0xce ||
        code == 0xcf) {
      unsigned char sof[5];
      if (length < 7 || pread(fd, sof, sizeof(sof), pos + 2) != sizeof(sof))
        return -1;
      *height = (unsigned)sof[1] << 8 | sof[2];
      *width = (unsigned)sof[3] << 8 | sof[4];
      return 0;
    }
    pos += length;
  }
}

static int validate_image(const char *path, unsigned *jpeg_size,
                          unsigned *width, unsigned *height) {
  unsigned char sig[2], tail[2];
  struct stat st;
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0 || fstat(fd, &st) < 0 || !S_ISREG(st.st_mode) || path[0] != '/' ||
      st.st_size < 4 || (uint64_t)st.st_size > MAX_JPEG_BYTES ||
      pread(fd, sig, sizeof(sig), 0) != sizeof(sig) ||
      pread(fd, tail, sizeof(tail), st.st_size - 2) != sizeof(tail) ||
      sig[0] != 0xff || sig[1] != 0xd8 || tail[0] != 0xff || tail[1] != 0xd9 ||
      image_dimensions(fd, width, height) < 0 || !*width || !*height ||
      *width > MAX_FRAME_WIDTH || *height > MAX_FRAME_HEIGHT) {
    if (fd >= 0)
      close(fd);
    ua_error(
        "Cannot use %s: choose a JPEG no larger than 600 KiB and 1920x1440 "
        "pixels",
        path);
    return -1;
  }
  close(fd);
  *jpeg_size = (unsigned)st.st_size;
  return 0;
}

static int link_path(const char *target, const char *path) {
  if (symlink(target, path) == 0 || errno == EEXIST)
    return 0;
  return -1;
}

static int make_configfs_dir(const char *path) {
  if (mkdir_p(path, 0755) == 0)
    return 0;
  ua_error("Cannot create UVC configfs directory %s: %s", path,
           strerror(errno));
  return -1;
}

static int write_configfs_attr(const char *dir, const char *attr,
                               const char *value) {
  if (write_attr(dir, attr, value) == 0)
    return 0;
  ua_error("Cannot write UVC configfs attribute %s/%s: %s", dir, attr,
           strerror(errno));
  return -1;
}

static int make_configfs_link(const char *target, const char *path) {
  if (link_path(target, path) == 0)
    return 0;
  ua_error("Cannot create UVC configfs link %s -> %s: %s", path, target,
           strerror(errno));
  return -1;
}

static int remove_link(const char *path) {
  struct stat st;
  if (lstat(path, &st) < 0)
    return errno == ENOENT || errno == ENOTDIR ? 0 : -1;
  if (!S_ISLNK(st.st_mode))
    return 0;
  return unlink(path);
}

static int remove_dir(const char *path) {
  if (rmdir(path) == 0 || errno == ENOENT || errno == ENOTDIR)
    return 0;
  return -1;
}

static int child_path(char *out, size_t size, const char *parent,
                      const char *name) {
  int n = snprintf(out, size, "%s/%s", parent, name);
  if (n < 0 || (size_t)n >= size) {
    errno = ENAMETOOLONG;
    return -1;
  }
  return 0;
}

int ua_webcam_remove(struct ua_gadget *g) {
  static const char *const speeds[] = {"fs", "hs", "ss"};
  static const char *const formats[] = {"m", "mjpeg"};
  char dir[PATH_MAX], path[PATH_MAX];
  if (child_path(path, sizeof(path), g->config, FUNC) < 0 ||
      remove_link(path) < 0)
    return -1;
  if (ua_gadget_func_dir(g, FUNC, dir, sizeof(dir)) < 0)
    return -1;

  if (child_path(path, sizeof(path), dir, "control/class") < 0)
    return -1;
  for (size_t i = 0; i < 2; i++) {
    char class_link[PATH_MAX], link[PATH_MAX];
    if (snprintf(class_link, sizeof(class_link), "%s/%s", path,
                 i == 0 ? "fs" : "ss") >= (int)sizeof(class_link) ||
        remove_link(class_link) < 0 ||
        snprintf(link, sizeof(link), "%s/h", class_link) >= (int)sizeof(link) ||
        remove_link(link) < 0)
      goto fail;
  }

  if (child_path(path, sizeof(path), dir, "streaming/class") < 0)
    return -1;
  for (size_t i = 0; i < sizeof(speeds) / sizeof(speeds[0]); i++) {
    char class_link[PATH_MAX], link[PATH_MAX];
    if (snprintf(class_link, sizeof(class_link), "%s/%s", path, speeds[i]) >=
            (int)sizeof(class_link) ||
        remove_link(class_link) < 0 ||
        snprintf(link, sizeof(link), "%s/h", class_link) >= (int)sizeof(link) ||
        remove_link(link) < 0)
      goto fail;
  }

  for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
    if (snprintf(path, sizeof(path), "%s/streaming/header/h/%s", dir,
                 formats[i]) >= (int)sizeof(path) ||
        remove_link(path) < 0 ||
        snprintf(path, sizeof(path), "%s/streaming/mjpeg/%s/frame", dir,
                 formats[i]) >= (int)sizeof(path) ||
        remove_dir(path) < 0 ||
        snprintf(path, sizeof(path), "%s/streaming/mjpeg/%s/480p", dir,
                 formats[i]) >= (int)sizeof(path) ||
        remove_dir(path) < 0 ||
        snprintf(path, sizeof(path), "%s/streaming/mjpeg/%s", dir,
                 formats[i]) >= (int)sizeof(path) ||
        remove_dir(path) < 0)
      goto fail;
  }
  if (snprintf(path, sizeof(path), "%s/streaming/header/h", dir) >=
          (int)sizeof(path) ||
      remove_dir(path) < 0 ||
      snprintf(path, sizeof(path), "%s/control/header/h", dir) >=
          (int)sizeof(path) ||
      remove_dir(path) < 0 || remove_dir(dir) < 0)
    goto fail;
  return 0;

fail:
  ua_error("Cannot clear UVC function %s: %s", dir, strerror(errno));
  return -1;
}

static int configure_function(struct ua_gadget *g, unsigned width,
                              unsigned height, unsigned frame_budget,
                              unsigned fps) {
  char dir[PATH_MAX], path[PATH_MAX], target[PATH_MAX], value[32];
  if (ua_gadget_add(g, FUNC, dir, sizeof(dir)) < 0)
    return -1;
  int n = snprintf(path, sizeof(path), "%s/streaming/mjpeg/m/frame", dir);
  if (n < 0 || n >= (int)sizeof(path)) {
    errno = ENAMETOOLONG;
    goto fail;
  }
  if (make_configfs_dir(path) < 0)
    return -1;
  snprintf(value, sizeof(value), "%u", width);
  if (write_configfs_attr(path, "wWidth", value) < 0)
    return -1;
  snprintf(value, sizeof(value), "%u", height);
  if (write_configfs_attr(path, "wHeight", value) < 0)
    return -1;
  snprintf(value, sizeof(value), "%u", frame_budget);
  if (write_configfs_attr(path, "dwMaxVideoFrameBufferSize", value) < 0)
    return -1;
  snprintf(value, sizeof(value), "%u", 10000000u / fps);
  if (write_configfs_attr(path, "dwFrameInterval", value) < 0)
    return -1;

  n = snprintf(path, sizeof(path), "%s/streaming/header/h", dir);
  if (n < 0 || n >= (int)sizeof(path)) {
    errno = ENAMETOOLONG;
    goto fail;
  }
  if (make_configfs_dir(path) < 0)
    return -1;
  if (child_path(target, sizeof(target), dir, "streaming/mjpeg/m") < 0)
    goto fail;
  n = snprintf(path, sizeof(path), "%s/streaming/header/h/m", dir);
  if (n < 0 || n >= (int)sizeof(path)) {
    errno = ENAMETOOLONG;
    goto fail;
  }
  if (make_configfs_link(target, path) < 0)
    return -1;

  if (child_path(target, sizeof(target), dir, "streaming/header/h") < 0)
    goto fail;
  static const char *const speeds[] = {"fs", "hs", "ss"};
  for (size_t i = 0; i < sizeof(speeds) / sizeof(speeds[0]); i++) {
    n = snprintf(path, sizeof(path), "%s/streaming/class/%s/h", dir, speeds[i]);
    if (n < 0 || n >= (int)sizeof(path)) {
      errno = ENAMETOOLONG;
      goto fail;
    }
    if (make_configfs_link(target, path) < 0)
      return -1;
  }
  n = snprintf(path, sizeof(path), "%s/control/header/h", dir);
  if (n < 0 || n >= (int)sizeof(path)) {
    errno = ENAMETOOLONG;
    goto fail;
  }
  if (make_configfs_dir(path) < 0)
    return -1;
  if (child_path(target, sizeof(target), dir, "control/header/h") < 0)
    goto fail;
  for (size_t i = 0; i < 2; i++) {
    n = snprintf(path, sizeof(path), "%s/control/class/%s/h", dir,
                 i == 0 ? "fs" : "ss");
    if (n < 0 || n >= (int)sizeof(path)) {
      errno = ENAMETOOLONG;
      goto fail;
    }
    if (make_configfs_link(target, path) < 0)
      return -1;
  }
  return 0;

fail:
  ua_error("Cannot configure the UVC function: %s", strerror(errno));
  return -1;
}

static int rollback_webcam(struct ua_gadget *g) {
  if (ua_webcam_remove(g) < 0) {
    ua_error("UVC cleanup failed; returning the USB controller to Android");
    ua_gadget_teardown(g);
    ua_crit_leave();
    return -1;
  }
  ua_running_clear(UA_FN_WEBCAM);
  return ua_gadget_commit(g);
}

static int start_worker(const char *image) {
  if (worker_pid() > 0)
    return 0;
  char log[PATH_MAX], pids[PATH_MAX], image_arg[PATH_MAX];
  ua_ws_path("Logs", "webcam.log", log, sizeof(log));
  pid_path(pids, sizeof(pids));
  safe_strncpy(image_arg, image, sizeof(image_arg));
  pid_t pid = fork();
  if (pid < 0) {
    ua_error("Cannot start the UVC frame feeder: %s", strerror(errno));
    return -1;
  }
  if (pid == 0) {
    ua_crit_child_release();
    sigset_t unblocked;
    sigemptyset(&unblocked);
    sigprocmask(SIG_SETMASK, &unblocked, NULL);
    setsid();
    int out = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    int null = open("/dev/null", O_RDONLY | O_CLOEXEC);
    if (out < 0 || null < 0)
      _exit(EXIT_FAILURE);
    if (out >= 0) {
      dup2(out, STDOUT_FILENO);
      dup2(out, STDERR_FILENO);
    }
    if (null >= 0)
      dup2(null, STDIN_FILENO);
    if (out > STDERR_FILENO)
      close(out);
    if (null > STDERR_FILENO)
      close(null);
    char *args[] = {"uvc-gadget", "-i", image_arg, UA_GADGET "/functions/" FUNC,
                    NULL};
    _exit(ua_uvc_gadget_main(4, args));
  }

  for (int i = 0; i < 30; i++) {
    int status;
    if (waitpid(pid, &status, WNOHANG) == pid) {
      ua_error("UVC frame feeder failed to start; see %s", log);
      return -1;
    }
    usleep(100000);
  }
  int fd =
      open(pids, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0 || dprintf(fd, "%d\n", (int)pid) < 0 || fsync(fd) < 0) {
    int err = errno;
    if (fd >= 0)
      close(fd);
    kill(pid, SIGTERM);
    ua_error("Cannot save the UVC feeder state: %s", strerror(err));
    return -1;
  }
  close(fd);
  return 0;
}

void ua_webcam_reapply(const struct ua_gadget *g) {
  struct ua_setup running;
  if (!ua_gadget_live(g) || !ua_gadget_has(g, FUNC) || worker_pid() > 0)
    return;
  ua_running_load(&running);
  if (running.webcam_image[0] && start_worker(running.webcam_image) < 0)
    ua_error("The UVC function is bound without a frame feeder; run '" UA_NAME
             " webcam apply' to retry");
}

static int do_start(const char *image, int apply) {
  struct ua_gadget g;
  unsigned width, height, jpeg_size;
  struct ua_setup setup;
  ua_setup_load(&setup);
  unsigned fps =
      setup.webcam_fps[0] ? (unsigned)strtoul(setup.webcam_fps, NULL, 10) : 30;
  if (fps != 15 && fps != 20 && fps != 24 && fps != 25 && fps != 30) {
    ua_error("Unsupported webcam frame rate: %u", fps);
    return UA_EXIT_USAGE;
  }
  unsigned frame_budget = MAX_JPEG_BYTES * 12u / fps;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_WEBCAM) < 0)
    return UA_EXIT_FAIL;
  if (!image[0]) {
    ua_error("Pick a JPEG frame first with '" UA_NAME
             " setup set webcam_image=PATH'");
    return UA_EXIT_FAIL;
  }
  if (validate_image(image, &jpeg_size, &width, &height) < 0)
    return UA_EXIT_FAIL;
  if (jpeg_size > frame_budget) {
    ua_error("The webcam JPEG exceeds the %u-byte frame budget at %u fps",
             frame_budget, fps);
    return UA_EXIT_FAIL;
  }
  if (ua_gadget_live(&g) && ua_gadget_has(&g, FUNC)) {
    struct ua_setup running;
    ua_running_load(&running);
    if (!strcmp(running.webcam_image, image) && worker_pid() > 0 &&
        !ua_setup_pending(UA_FN_WEBCAM, &setup, &running)) {
      ua_log("Webcam already runs its still image");
      return UA_EXIT_OK;
    }
    if (!apply) {
      ua_error("Webcam is already running; use '" UA_NAME " webcam apply'");
      return UA_EXIT_FAIL;
    }
  }

  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  if (ua_gadget_has(&g, FUNC) && ua_webcam_remove(&g) < 0)
    goto fail;
  if (configure_function(&g, width, height, frame_budget, fps) < 0)
    goto fail;
  if (ua_running_take(UA_FN_WEBCAM, 0) < 0) {
    ua_error("Cannot save the running webcam setup: %s", strerror(errno));
    goto fail;
  }
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  if (worker_pid() <= 0 && start_worker(image) < 0) {
    if (ua_gadget_begin(&g) < 0) {
      ua_error("Cannot begin UVC rollback; returning the USB controller to "
               "Android");
      ua_gadget_teardown(&g);
      return UA_EXIT_FAIL;
    }
    rollback_webcam(&g);
    return UA_EXIT_FAIL;
  }
  ua_log("Serving %ux%u MJPEG frames at %u fps as a UVC webcam", width, height,
         fps);
  return UA_EXIT_OK;

fail:
  rollback_webcam(&g);
  return UA_EXIT_FAIL;
}

static int do_stop(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, FUNC)) {
    ua_log("Webcam is not running");
    ua_running_clear(UA_FN_WEBCAM);
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  if (ua_webcam_remove(&g) < 0) {
    ua_error("UVC cleanup failed; returning the USB controller to Android");
    ua_gadget_teardown(&g);
    ua_crit_leave();
    return UA_EXIT_FAIL;
  }
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_running_clear(UA_FN_WEBCAM);
  ua_log("Webcam removed");
  return UA_EXIT_OK;
}

void ua_help_webcam(void) {
  printf("Usage: " UA_NAME " webcam <start|apply|stop> [JPEG]\n\n"
         "Present a still image to a USB host as a UVC webcam. The image\n"
         "must be an absolute JPEG no larger than 600 KiB and 1920x1440. The\n"
         "Android app prepares the selected source. UVC uses webcam_fps and\n"
         "the image dimensions. The kernel must provide "
         "CONFIG_USB_CONFIGFS_F_UVC.\n\n"
         "  start [JPEG]  Start from the saved setup, or save JPEG first\n"
         "  apply         Apply the saved image, restarting the webcam\n"
         "  stop          Remove the webcam function\n");
}

int ua_cmd_webcam(int argc, char **argv) {
  if (ua_want_help(argc, argv)) {
    ua_help_webcam();
    return UA_EXIT_OK;
  }
  if (argc < 2 || argc > 3) {
    ua_usage_error("webcam", "webcam takes start, apply or stop, and an "
                             "optional JPEG path");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(argv[1], "stop")) {
    if (argc != 2) {
      ua_usage_error("webcam", "stop takes no arguments");
      return UA_EXIT_USAGE;
    }
    return do_stop();
  }
  if (strcmp(argv[1], "start") && strcmp(argv[1], "apply")) {
    ua_usage_error("webcam", "Unknown action '%s'", argv[1]);
    return UA_EXIT_USAGE;
  }
  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  struct ua_setup setup;
  ua_setup_load(&setup);
  if (argc == 3) {
    char assignment[PATH_MAX + 16];
    if (snprintf(assignment, sizeof(assignment), "webcam_image=%s", argv[2]) >=
        (int)sizeof(assignment)) {
      ua_error("JPEG path is too long");
      return UA_EXIT_USAGE;
    }
    if (ua_setup_set(&setup, assignment) < 0 ||
        ua_setup_set(&setup, "webcam_source=image") < 0 ||
        ua_setup_save(&setup) < 0)
      return UA_EXIT_USAGE;
  }
  return do_start(setup.webcam_image, !strcmp(argv[1], "apply"));
}

void ua_status_webcam(const struct ua_gadget *g, int json) {
  if (!ua_gadget_has(g, FUNC)) {
    if (json)
      printf("null");
    return;
  }
  struct ua_setup running;
  ua_running_load(&running);
  if (json) {
    int first = 1;
    printf("{");
    ua_json_str("function", "uvc", &first);
    ua_json_str("source", running.webcam_source, &first);
    ua_json_str("image", running.webcam_image, &first);
    ua_json_int("running", worker_pid() > 0, &first);
    printf("}");
  } else {
    printf("Webcam:     %s%s%s\n",
           running.webcam_source[0] ? running.webcam_source : "image",
           worker_pid() > 0 ? " " : " (no feeder); ", running.webcam_image);
  }
}
