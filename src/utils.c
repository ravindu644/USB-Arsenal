/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The file, process and logging helpers are lifted from Droidspaces
 * (src/utils.c, src/android/android.c) by the same author.
 */

#include "usbarsenal.h"

int ua_verbose;

void ua_log_internal(const char *prefix, const char *color, int is_err,
                     const char *fmt, ...) {
  char msg[4096];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);

  if (!is_err && !ua_verbose && msg[0] == '[' &&
      (strncmp(msg, "[DEBUG]", 7) == 0 || strncmp(msg, "[IPT]", 5) == 0 ||
       strncmp(msg, "[NET]", 5) == 0 || strncmp(msg, "[DHCP]", 6) == 0))
    return;

  /* Colour only for a terminal, so logs piped to a file or an app stay clean */
  FILE *out = is_err ? stderr : stdout;
  if (isatty(fileno(out)))
    fprintf(out, "[%s%s" C_RESET "] %s\n", color, prefix, msg);
  else
    fprintf(out, "[%s] %s\n", prefix, msg);
  fflush(out);
}

void safe_strncpy(char *dst, const char *src, size_t size) {
  if (!dst || size == 0)
    return;
  snprintf(dst, size, "%s", src ? src : "");
}

int mkdir_p(const char *path, mode_t mode) {
  char tmp[PATH_MAX];
  int r = snprintf(tmp, sizeof(tmp), "%s", path);
  if (r < 0 || (size_t)r >= sizeof(tmp)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  for (char *p = tmp + 1; *p; p++) {
    if (*p == '/') {
      *p = '\0';
      if (mkdir(tmp, mode) < 0 && errno != EEXIST)
        return -1;
      *p = '/';
    }
  }
  if (mkdir(tmp, mode) < 0 && errno != EEXIST)
    return -1;
  return 0;
}

ssize_t write_all(int fd, const void *buf, size_t count) {
  const char *p = buf;
  size_t remaining = count;
  while (remaining > 0) {
    ssize_t w = write(fd, p, remaining);
    if (w < 0) {
      if (errno == EINTR)
        continue;
      return -1;
    }
    p += w;
    remaining -= (size_t)w;
  }
  return (ssize_t)count;
}

/* No O_CREAT: every write here lands on a configfs or sysfs attribute, and a
 * typo in a path must fail with ENOENT, not create a stray file. State files
 * go through stdio instead. errno is kept for callers that care which
 * configfs check rejected the write. */
int write_file(const char *path, const char *content) {
  int fd = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
  if (fd < 0)
    return -1;
  size_t len = strlen(content);
  ssize_t w = write_all(fd, content, len);
  int saved = errno;
  close(fd);
  errno = saved;
  return w == (ssize_t)len ? 0 : -1;
}

int read_file(const char *path, char *buf, size_t size) {
  if (size == 0)
    return -1;
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return -1;
  ssize_t total = 0, r = 1;
  while ((size_t)total < size - 1 &&
         (r = read(fd, buf + total, size - 1 - (size_t)total)) > 0)
    total += r;
  close(fd);
  if (r < 0)
    return -1;
  buf[total] = '\0';
  while (total > 0 && (buf[total - 1] == '\n' || buf[total - 1] == '\r'))
    buf[--total] = '\0';
  return (int)total;
}

int grep_file(const char *path, const char *pattern) {
  char buf[16384];
  if (read_file(path, buf, sizeof(buf)) < 0)
    return -1;
  return strstr(buf, pattern) ? 1 : 0;
}

static int internal_run(char *const argv[], int quiet) {
  pid_t pid = fork();
  if (pid < 0)
    return -1;
  if (pid == 0) {
    if (quiet) {
      int devnull = open("/dev/null", O_RDWR);
      if (devnull >= 0) {
        dup2(devnull, 1);
        dup2(devnull, 2);
        close(devnull);
      }
    }
    execvp(argv[0], argv);
    _exit(127);
  }
  int status;
  while (waitpid(pid, &status, 0) < 0)
    if (errno != EINTR)
      return -1;
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

int run_command(char *const argv[]) { return internal_run(argv, 0); }
int run_command_quiet(char *const argv[]) { return internal_run(argv, 1); }

/* stdout of argv into out, trailing newlines stripped. musl's popen() runs
 * /bin/sh, which Android does not have, so fork and exec it ourselves. */
int run_capture(char *const argv[], char *out, size_t size) {
  int pfd[2];
  if (size == 0 || pipe(pfd) < 0)
    return -1;
  pid_t pid = fork();
  if (pid < 0) {
    close(pfd[0]);
    close(pfd[1]);
    return -1;
  }
  if (pid == 0) {
    close(pfd[0]);
    dup2(pfd[1], STDOUT_FILENO);
    close(pfd[1]);
    int devnull = open("/dev/null", O_WRONLY);
    if (devnull >= 0) {
      dup2(devnull, STDERR_FILENO);
      close(devnull);
    }
    execvp(argv[0], argv);
    _exit(127);
  }
  close(pfd[1]);
  size_t total = 0;
  ssize_t r;
  while (total < size - 1 &&
         (r = read(pfd[0], out + total, size - 1 - total)) != 0) {
    if (r < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    total += (size_t)r;
  }
  close(pfd[0]);
  out[total] = '\0';
  while (total > 0 && (out[total - 1] == '\n' || out[total - 1] == '\r'))
    out[--total] = '\0';
  int status;
  while (waitpid(pid, &status, 0) < 0)
    if (errno != EINTR)
      return -1;
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

/* run_command_log: stderr and stdout of argv go to the [IPT] debug log, so an
 * iptables failure is visible with -v without cluttering normal runs. */
int run_command_log(char *const argv[]) {
  int pfd[2];
  if (pipe(pfd) < 0)
    return internal_run(argv, 0);
  pid_t pid = fork();
  if (pid < 0) {
    close(pfd[0]);
    close(pfd[1]);
    return -1;
  }
  if (pid == 0) {
    close(pfd[0]);
    dup2(pfd[1], STDOUT_FILENO);
    dup2(pfd[1], STDERR_FILENO);
    close(pfd[1]);
    execvp(argv[0], argv);
    _exit(127);
  }
  close(pfd[1]);
  char buf[512];
  FILE *f = fdopen(pfd[0], "r");
  if (f) {
    while (fgets(buf, sizeof(buf), f)) {
      buf[strcspn(buf, "\r\n")] = '\0';
      if (buf[0])
        ua_log("[IPT] %s", buf);
    }
    fclose(f);
  } else {
    close(pfd[0]);
  }
  int status;
  while (waitpid(pid, &status, 0) < 0)
    if (errno != EINTR)
      return -1;
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}

int ds_thread_create(pthread_t *tid, void *(*fn)(void *), void *arg) {
  sigset_t all, old;
  sigfillset(&all);
  pthread_sigmask(SIG_BLOCK, &all, &old);
  int ret = pthread_create(tid, NULL, fn, arg);
  pthread_sigmask(SIG_SETMASK, &old, NULL);
  return ret;
}

int is_android(void) {
  static int cached = -1;
  if (cached != -1)
    return cached;
  /* Recovery (TWRP) has /system mounted at times but no init USB HAL to hand
   * the controller back to, so treat it as plain Linux */
  if (access("/system/bin/recovery", F_OK) == 0)
    cached = 0;
  else
    cached = access("/system/build.prop", F_OK) == 0 ||
             access("/system/bin/app_process", F_OK) == 0;
  return cached;
}

/* argv wants char *, our callers hold const strings: copy, do not cast */
int ua_getprop(const char *name, char *out, size_t size) {
  char n[128];
  safe_strncpy(n, name, sizeof(n));
  char *argv[] = {"getprop", n, NULL};
  out[0] = '\0';
  return run_capture(argv, out, size) == 0 ? 0 : -1;
}

int ua_setprop(const char *name, const char *value) {
  char n[128], v[256];
  safe_strncpy(n, name, sizeof(n));
  safe_strncpy(v, value, sizeof(v));
  char *argv[] = {"setprop", n, v, NULL};
  return run_command_quiet(argv) == 0 ? 0 : -1;
}

static void sleep_ms(int ms) {
  struct timespec ts = {ms / 1000, (long)(ms % 1000) * 1000000L};
  while (nanosleep(&ts, &ts) < 0 && errno == EINTR)
    ;
}

/* Poll until path reads want, or the timeout runs out. 0 on match. */
int ua_wait_file(const char *path, const char *want, int timeout_ms) {
  char buf[256];
  for (int waited = 0;; waited += 100) {
    if (read_file(path, buf, sizeof(buf)) >= 0 && strcmp(buf, want) == 0)
      return 0;
    if (waited >= timeout_ms)
      return -1;
    sleep_ms(100);
  }
}

int ua_wait_prop(const char *name, const char *want, int timeout_ms) {
  char buf[256];
  for (int waited = 0;; waited += 100) {
    if (ua_getprop(name, buf, sizeof(buf)) == 0 && strcmp(buf, want) == 0)
      return 0;
    if (waited >= timeout_ms)
      return -1;
    sleep_ms(100);
  }
}

/* /run is tmpfs on a normal Linux, so a reboot clears stale state the same
 * way it clears the gadget. Android has no /run; /data/local/tmp survives a
 * reboot, which is harmless because `stop` checks configfs before acting. */
const char *ua_state_dir(void) {
  static char dir[64];
  if (!dir[0]) {
    safe_strncpy(dir,
                 is_android()                ? "/data/local/tmp/usb-arsenal"
                 : access("/run", W_OK) == 0 ? "/run/usb-arsenal"
                                             : "/tmp/usb-arsenal",
                 sizeof(dir));
    mkdir_p(dir, 0700);
    /* Root writes the handoff here. In a world-writable parent anyone could
     * have planted a symlink or their own directory first; refuse rather
     * than follow it. This runs before anything changes hands. */
    struct stat st;
    if (lstat(dir, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != geteuid() ||
        (st.st_mode & 022)) {
      ua_error("Unsafe state directory %s (not a directory owned by us, or "
               "writable by others)",
               dir);
      exit(UA_EXIT_FAIL);
    }
  }
  return dir;
}

void ua_state_path(const char *leaf, char *out, size_t size) {
  snprintf(out, size, "%s/%s", ua_state_dir(), leaf);
}
