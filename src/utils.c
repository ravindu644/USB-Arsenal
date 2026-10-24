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

/* One log for every run, the app's or a shell's, and the tether daemon's:
 * Logs/usb-arsenal.log, a timestamped line per message, tagged ones too.
 * Each line is one open, write and close: O_APPEND lands it whole beside
 * another process's, and the daemon follows a rotation by opening the
 * path afresh. ponytail: two runs rotating in the same instant may lose the
 * old file; a lock is not worth a log */
static char log_path[PATH_MAX];
static char log_who[32];

static void log_line(const char *prefix, const char *msg) {
  char line[4096 + 96], stamp[32];
  struct timespec ts;
  struct tm tm;
  if (!log_path[0])
    return;
  /* UTC, marked: a static binary has no zone data, the app shows local time */
  clock_gettime(CLOCK_REALTIME, &ts);
  gmtime_r(&ts.tv_sec, &tm);
  strftime(stamp, sizeof(stamp), "%Y-%m-%dT%H:%M:%S", &tm);
  int n = snprintf(line, sizeof(line), "%s.%03ldZ %s[%d] [%s] %s\n", stamp,
                   ts.tv_nsec / 1000000, log_who, (int)getpid(), prefix, msg);
  int fd = open(log_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0600);
  if (fd < 0)
    return;
  if (n > 0 && write(fd, line, (size_t)n) < 0)
    log_path[0] = '\0';
  close(fd);
}

void ua_log_open(const char *who, const char *invocation) {
  char old[PATH_MAX];
  struct stat st;
  if (geteuid() != 0)
    return;
  ua_ws_path("Logs", "usb-arsenal.log", log_path, sizeof(log_path));
  /* Past 1 MiB the file steps aside; one old file stays */
  if (stat(log_path, &st) == 0 && st.st_size > 1024 * 1024) {
    ua_ws_path("Logs", "usb-arsenal.log.1", old, sizeof(old));
    rename(log_path, old);
  }
  safe_strncpy(log_who, who, sizeof(log_who));
  /* The command line as a prompt line, in the file only: whoever typed it
   * has it on screen already */
  log_line("$", invocation);
}

void ua_log_internal(const char *prefix, const char *color, int is_err,
                     const char *fmt, ...) {
  char msg[4096];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);

  log_line(prefix, msg);
  if (!is_err && !ua_verbose && msg[0] == '[' &&
      (strncmp(msg, "[DEBUG]", 7) == 0 || strncmp(msg, "[IPT]", 5) == 0 ||
       strncmp(msg, "[NET]", 5) == 0 || strncmp(msg, "[DHCP]", 6) == 0))
    return;

  /* Always coloured, as in Droidspaces: the app reads it through a pipe and
   * strips the codes itself */
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
static int write_path(const char *path, const void *buf, size_t len) {
  int fd = open(path, O_WRONLY | O_TRUNC | O_CLOEXEC);
  if (fd < 0)
    return -1;
  ssize_t w = write_all(fd, buf, len);
  int saved = errno;
  close(fd);
  errno = saved;
  return w == (ssize_t)len ? 0 : -1;
}

int write_file(const char *path, const char *content) {
  return write_path(path, content, strlen(content));
}

/* <dir>/<attr>, for the binary attributes f_hid takes */
int write_attr_buf(const char *dir, const char *attr, const void *buf,
                   size_t len) {
  char p[PATH_MAX];
  int n = snprintf(p, sizeof(p), "%s/%s", dir, attr);
  if (n < 0 || (size_t)n >= sizeof(p)) {
    errno = ENAMETOOLONG;
    return -1;
  }
  return write_path(p, buf, len);
}

/* Logged with -v when the kernel refuses it; errno survives the log, since
 * callers tell EBUSY (a host's medium lock) from the rest */
int write_attr(const char *dir, const char *attr, const char *val) {
  if (write_attr_buf(dir, attr, val, strlen(val)) == 0)
    return 0;
  int saved = errno;
  ua_debug("write %s/%s <- %s: %s", dir, attr, val, strerror(saved));
  errno = saved;
  return -1;
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

static int wait_exit(pid_t pid) {
  int status;
  while (waitpid(pid, &status, 0) < 0)
    if (errno != EINTR)
      return -1;
  return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
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
  return wait_exit(pid);
}

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
  return wait_exit(pid);
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
  return wait_exit(pid);
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

/* Poll until get(key) reads want, or the timeout runs out. 0 on match. get is
 * read_file or ua_getprop. */
int ua_wait(int (*get)(const char *, char *, size_t), const char *key,
            const char *want, int timeout_ms) {
  char buf[256];
  for (int waited = 0;; waited += 100) {
    if (get(key, buf, sizeof(buf)) >= 0 && strcmp(buf, want) == 0)
      return 0;
    if (waited >= timeout_ms)
      return -1;
    sleep_ms(100);
  }
}

/* One workspace, laid out like Droidspaces' /data/local/Droidspaces: Logs,
 * Pids, Net, plus Gadget (handoff, lock, setup). A container gets the Linux
 * path, where Android's is best bind-mounted, so both share one handoff.
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
 * program name, not inode: replacing the binary while the tether process
 * runs (adb push, an update) leaves its exe as "... (deleted)", and a stop may
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
