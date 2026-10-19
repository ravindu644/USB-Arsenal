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

  /* Always coloured, as in Droidspaces: a relayed command prints through the
   * daemon's pipe, so an isatty() check here would strip it on the way back */
  FILE *out = is_err ? stderr : stdout;
  fprintf(out, "[%s%s" C_RESET "] %s\n", color, prefix, msg);
  fflush(out);
}

/* Droidspaces' JSON encoder (src/status.c): flat objects of strings and ints
 * are all `check --json` needs */
void ua_json_str(const char *key, const char *val, int *first) {
  printf("%s\"%s\":\"", *first ? "" : ",", key);
  *first = 0;
  for (const unsigned char *p = (const unsigned char *)val; val && *p; p++) {
    if (*p == '"' || *p == '\\')
      printf("\\%c", *p);
    else if (*p < 0x20)
      printf("\\u%04x", *p);
    else
      putchar(*p);
  }
  putchar('"');
}

void ua_json_int(const char *key, long long val, int *first) {
  printf("%s\"%s\":%lld", *first ? "" : ",", key, val);
  *first = 0;
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

/* One workspace, laid out like Droidspaces' /data/local/Droidspaces: Logs,
 * Pids, Net, plus Gadget (handoff, lock) and Run (the daemon socket). A
 * container gets the Linux path; its Run/ is where the host's is bind-mounted.
 * Persistent on both; a stale handoff is voided by its boot marker. */
const char *ua_workspace(void) {
  return is_android() ? UA_WORKSPACE_ANDROID : UA_WORKSPACE_LINUX;
}

/* Root writes the handoff here. If anyone else owned the workspace, or could
 * write to it, they could plant a symlink for root to follow; refuse instead.
 * Runs before anything changes hands. */
static void ensure_workspace(void) {
  static int done;
  struct stat st;
  const char *ws = ua_workspace();
  if (done)
    return;
  mkdir_p(ws, 0755);
  if (lstat(ws, &st) < 0 || !S_ISDIR(st.st_mode) || st.st_uid != 0 ||
      (st.st_mode & 022)) {
    ua_error("Unsafe workspace %s (not a directory owned by root, or "
             "writable by others)",
             ws);
    exit(UA_EXIT_FAIL);
  }
  done = 1;
}

/* <workspace>/<sub>/<leaf>, sub may be NULL. As root the directories are
 * made on the way; anyone else (a client in the usb-arsenal group) only gets
 * the path. */
void ua_ws_path(const char *sub, const char *leaf, char *out, size_t size) {
  if (geteuid() == 0) {
    ensure_workspace();
    if (sub) {
      snprintf(out, size, "%s/%s", ua_workspace(), sub);
      mkdir(out, 0755);
    }
  }
  if (sub)
    snprintf(out, size, "%s/%s/%s", ua_workspace(), sub, leaf);
  else
    snprintf(out, size, "%s/%s", ua_workspace(), leaf);
}

/* The pid in pidfile, if that process is still one of ours. Matched by
 * program name, not inode: replacing the binary while a daemon runs (adb
 * push, a module update) leaves its exe as "... (deleted)", and a stop may
 * come from a different copy than the start. A pid reused by something else
 * after a reboot must never get our signal. */
pid_t ua_read_our_pid(const char *pidfile) {
  char p[PATH_MAX], buf[32], exe[PATH_MAX], self[PATH_MAX];
  if (read_file(pidfile, buf, sizeof(buf)) <= 0)
    return 0;
  pid_t pid = (pid_t)strtol(buf, NULL, 10);
  if (pid <= 0)
    return 0;
  snprintf(p, sizeof(p), "/proc/%d/exe", (int)pid);
  ssize_t a = readlink(p, exe, sizeof(exe) - 1);
  ssize_t b = readlink("/proc/self/exe", self, sizeof(self) - 1);
  if (a <= 0 || b <= 0)
    return 0;
  exe[a] = self[b] = '\0';
  char *del = strstr(exe, " (deleted)");
  if (del)
    *del = '\0';
  const char *x = strrchr(exe, '/'), *y = strrchr(self, '/');
  return strcmp(x ? x + 1 : exe, y ? y + 1 : self) == 0 ? pid : 0;
}

/* SCM_RIGHTS helpers, lifted from Droidspaces (src/utils.c) */

int ua_send_fds(int sock, const void *data, size_t len, const int *fds,
                int count) {
  if (count < 0 || count > UA_MAX_FDS)
    return -1;
  struct msghdr msg = {0};
  char buf[CMSG_SPACE(sizeof(int) * UA_MAX_FDS)];
  memset(buf, 0, sizeof(buf));
  struct iovec io = {.iov_base = (void *)(uintptr_t)data, .iov_len = len};
  msg.msg_iov = &io;
  msg.msg_iovlen = 1;
  if (count > 0) {
    msg.msg_control = buf;
    msg.msg_controllen = CMSG_SPACE(sizeof(int) * (size_t)count);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int) * (size_t)count);
    memcpy(CMSG_DATA(cmsg), fds, sizeof(int) * (size_t)count);
  }
  ssize_t n = sendmsg(sock, &msg, MSG_NOSIGNAL);
  return n == (ssize_t)len ? 0 : -1;
}

/* Up to max fds with the message; extras the sender attached are closed,
 * since the kernel has already installed them here. Returns the data bytes
 * read, -1 on error or a truncated control message. */
ssize_t ua_recv_fds(int sock, void *data, size_t len, int *fds, int max,
                    int *count) {
  struct msghdr msg = {0};
  char buf[CMSG_SPACE(sizeof(int) * UA_MAX_FDS)];
  memset(buf, 0, sizeof(buf));
  struct iovec io = {.iov_base = data, .iov_len = len};
  msg.msg_iov = &io;
  msg.msg_iovlen = 1;
  msg.msg_control = buf;
  msg.msg_controllen = sizeof(buf);
  *count = 0;
  ssize_t n = recvmsg(sock, &msg, MSG_CMSG_CLOEXEC);
  if (n < 0)
    return -1;
  for (struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg;
       cmsg = CMSG_NXTHDR(&msg, cmsg)) {
    if (cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS)
      continue;
    int got = (int)((cmsg->cmsg_len - CMSG_LEN(0)) / sizeof(int));
    const int *src = (const int *)CMSG_DATA(cmsg);
    for (int i = 0; i < got; i++) {
      int fd;
      memcpy(&fd, src + i, sizeof(fd));
      if (*count < max)
        fds[(*count)++] = fd;
      else
        close(fd);
    }
  }
  if (msg.msg_flags & MSG_CTRUNC) {
    for (int i = 0; i < *count; i++)
      close(fds[i]);
    *count = 0;
    return -1;
  }
  return n;
}

/* Something stable and unique to this machine: the phone's serial, else the
 * machine id. The default USB serial and the tether MACs come from it, so a
 * host keeps recognising the device and two of them never collide. */
void ua_device_seed(char *out, size_t size) {
  out[0] = '\0';
  if (is_android())
    ua_getprop("ro.serialno", out, size);
  if (!out[0])
    read_file("/etc/machine-id", out, size);
  if (!out[0])
    safe_strncpy(out, UA_NAME, size);
}
