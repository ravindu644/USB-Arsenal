/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `usb-arsenal daemon`: one root process on the host owns the controller,
 * configfs, usb0 and NAT; every other copy of the binary (an Android shell,
 * a container, a chroot) relays its command line here. Ported from
 * Droidspaces' daemon (src/daemon.c), with two deliberate differences:
 *
 *   - a filesystem socket, not an abstract one. Abstract sockets belong to a
 *     network namespace, so a container with its own could never connect;
 *     a path crosses namespaces, and a bind mount of Run/ carries it into a
 *     container's mount namespace.
 *   - no "same pid namespace" rule. That rule is exactly what would refuse a
 *     container client. Root or the usb-arsenal group decides instead.
 *
 * Wire protocol, big-endian:
 *   request  u32 magic "UARS" | u16 version | u16 nfds | u32 argc,
 *            then argc x { u32 len | bytes }; the fds ride on the first
 *            sendmsg and land as fd 3, 4, ... in the command
 *   frames   u8 type | u32 len | payload (OUT, ERR, EXIT with a u32 code)
 */

#include "usbarsenal.h"
#include <grp.h>
#include <sys/signalfd.h>
#include <sys/un.h>

#ifndef SO_PEERGROUPS
#define SO_PEERGROUPS 59
#endif

#define UA_MAGIC 0x55415253u /* "UARS" */
#define UA_PROTO 1
#define MAX_ARGC 64
#define MAX_ARG 8192
#define IOBUF 8192

#define MSG_OUT ((uint8_t)0x01)
#define MSG_ERR ((uint8_t)0x02)
#define MSG_EXIT ((uint8_t)0xFF)

static char self_path[PATH_MAX];
static gid_t ua_gid = UA_DAEMON_GID;

void ua_socket_path(char *out, size_t size) {
  const char *env = getenv("UA_SOCKET");
  if (env && env[0])
    safe_strncpy(out, env, size);
  else
    ua_ws_path("Run", "usb-arsenal.sock", out, size);
}

static socklen_t make_addr(struct sockaddr_un *addr) {
  memset(addr, 0, sizeof(*addr));
  addr->sun_family = AF_UNIX;
  ua_socket_path(addr->sun_path, sizeof(addr->sun_path));
  return (socklen_t)sizeof(*addr);
}

static int read_exact(int fd, void *buf, size_t n) {
  uint8_t *p = buf;
  while (n) {
    ssize_t r = read(fd, p, n);
    if (r < 0 && errno == EINTR)
      continue;
    if (r <= 0)
      return -1;
    p += r;
    n -= (size_t)r;
  }
  return 0;
}

static int send_frame(int fd, uint8_t type, const void *data, uint32_t len) {
  uint8_t hdr[5];
  uint32_t nl = htonl(len);
  hdr[0] = type;
  memcpy(hdr + 1, &nl, 4);
  if (write_all(fd, hdr, 5) < 0)
    return -1;
  return (len && write_all(fd, data, len) < 0) ? -1 : 0;
}

static void send_exit(int fd, int code) {
  uint32_t nc = htonl((uint32_t)code);
  send_frame(fd, MSG_EXIT, &nc, 4);
}

static void send_err(int fd, const char *msg) {
  send_frame(fd, MSG_ERR, msg, (uint32_t)strlen(msg));
  send_exit(fd, UA_EXIT_FAIL);
}

int ua_daemon_probe(void) {
  struct sockaddr_un addr;
  socklen_t alen = make_addr(&addr);
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0)
    return 0;
  int alive = connect(s, (struct sockaddr *)&addr, alen) == 0;
  close(s);
  return alive;
}

/* Does a "Groups:" line in /proc/<pid>/status list gid */
static int proc_has_group(pid_t pid, gid_t gid) {
  char p[64], buf[4096], *g;
  snprintf(p, sizeof(p), "/proc/%d/status", (int)pid);
  if (read_file(p, buf, sizeof(buf)) <= 0 || !(g = strstr(buf, "\nGroups:")))
    return 0;
  for (g += 8; *g && *g != '\n';) {
    char *end;
    unsigned long v = strtoul(g, &end, 10);
    if (end == g)
      break;
    if ((gid_t)v == gid)
      return 1;
    g = end;
  }
  return 0;
}

/* Root, or a member of the usb-arsenal group. Fails closed: no credentials,
 * no entry. The socket's 0660 root:<gid> mode already stopped everyone else
 * at connect(); this is the second gate. Supplementary groups come from
 * SO_PEERGROUPS (4.13+), or from /proc when the peer shares our pid
 * namespace; a container client on an old kernel needs the group as its
 * primary gid. */
static int peer_authorized(int fd) {
  struct ucred cred;
  socklen_t clen = sizeof(cred);
  if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &clen) < 0 ||
      clen < sizeof(cred))
    return 0;
  if (cred.uid == 0 || cred.gid == ua_gid)
    return 1;

  gid_t groups[256];
  socklen_t glen = sizeof(groups);
  if (getsockopt(fd, SOL_SOCKET, SO_PEERGROUPS, groups, &glen) == 0)
    for (size_t i = 0; i < glen / sizeof(gid_t); i++)
      if (groups[i] == ua_gid)
        return 1;
  return cred.pid > 0 && proc_has_group(cred.pid, ua_gid);
}

struct request {
  int argc, nfds, fds[UA_MAX_FDS];
  char *argv[MAX_ARGC + 2];
};

static int recv_request(int conn, struct request *r) {
  uint8_t hdr[12];
  memset(r, 0, sizeof(*r));
  ssize_t n = ua_recv_fds(conn, hdr, sizeof(hdr), r->fds, UA_MAX_FDS, &r->nfds);
  if (n <= 0 || (n < (ssize_t)sizeof(hdr) &&
                 read_exact(conn, hdr + n, sizeof(hdr) - (size_t)n) < 0))
    return -1;
  uint32_t magic, argc;
  uint16_t ver, nfds;
  memcpy(&magic, hdr, 4);
  memcpy(&ver, hdr + 4, 2);
  memcpy(&nfds, hdr + 6, 2);
  memcpy(&argc, hdr + 8, 4);
  argc = ntohl(argc);
  if (ntohl(magic) != UA_MAGIC || ntohs(ver) != UA_PROTO ||
      ntohs(nfds) != r->nfds || !argc || argc > MAX_ARGC)
    return -1;
  r->argv[0] = UA_NAME;
  for (uint32_t i = 1; i <= argc; i++) {
    uint32_t len;
    if (read_exact(conn, &len, 4) < 0 || (len = ntohl(len)) > MAX_ARG ||
        !(r->argv[i] = malloc(len + 1)) ||
        (len && read_exact(conn, r->argv[i], len) < 0))
      return -1;
    r->argv[i][len] = '\0';
    r->argc = (int)i + 1;
  }
  return 0;
}

static void drain(int fd, int conn, uint8_t type) {
  char buf[IOBUF];
  ssize_t n;
  fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
  while ((n = read(fd, buf, sizeof(buf))) > 0)
    send_frame(conn, type, buf, (uint32_t)n);
}

/* Run one command for one client: the same binary, re-executed with
 * UA_NO_PROXY so it acts instead of relaying, its output streamed back. */
static void session(int conn, struct request *r) {
  int out[2], err[2];
  if (pipe2(out, O_CLOEXEC) < 0 || pipe2(err, O_CLOEXEC) < 0) {
    send_err(conn, "daemon: pipe failed\n");
    return;
  }
  pid_t child = fork();
  if (child < 0) {
    send_err(conn, "daemon: fork failed\n");
    return;
  }
  if (child == 0) {
    dup2(out[1], STDOUT_FILENO);
    dup2(err[1], STDERR_FILENO);
    int null = open("/dev/null", O_RDONLY);
    if (null >= 0)
      dup2(null, STDIN_FILENO);
    /* The client's fds become 3, 4, ...: that is how it named them */
    for (int i = 0; i < r->nfds; i++)
      if (dup2(r->fds[i], 3 + i) < 0)
        _exit(UA_EXIT_FAIL);
    setenv("UA_NO_PROXY", "1", 1);
    execv(self_path[0] ? self_path : "/proc/self/exe", r->argv);
    _exit(127);
  }
  close(out[1]);
  close(err[1]);
  for (int i = 0; i < r->nfds; i++)
    close(r->fds[i]);

  struct pollfd pfd[3] = {
      {out[0], POLLIN, 0}, {err[0], POLLIN, 0}, {conn, POLLIN, 0}};
  int status = 0, done = 0;
  while (!done) {
    if (poll(pfd, 3, 200) < 0 && errno != EINTR)
      break;
    for (int i = 0; i < 2; i++)
      if (pfd[i].fd >= 0 && (pfd[i].revents & (POLLIN | POLLHUP))) {
        char buf[IOBUF];
        ssize_t n = read(pfd[i].fd, buf, sizeof(buf));
        if (n > 0)
          send_frame(conn, i ? MSG_ERR : MSG_OUT, buf, (uint32_t)n);
        else if (n == 0)
          pfd[i].fd = -1;
      }
    /* The client went away: stop its command rather than run it blind */
    if (pfd[2].revents & (POLLIN | POLLHUP | POLLERR)) {
      char c;
      if (recv(conn, &c, 1, MSG_DONTWAIT | MSG_PEEK) <= 0)
        kill(child, SIGTERM);
      pfd[2].fd = -1;
    }
    /* A daemonizing command (tether start) leaves a grandchild holding
     * nothing of ours, but finish on the child's exit anyway, not on EOF */
    if (waitpid(child, &status, WNOHANG) == child)
      done = 1;
  }
  if (!done)
    waitpid(child, &status, 0);
  drain(out[0], conn, MSG_OUT);
  drain(err[0], conn, MSG_ERR);
  close(out[0]);
  close(err[0]);
  send_exit(conn, WIFEXITED(status) ? WEXITSTATUS(status) : UA_EXIT_FAIL);
}

static void handle_conn(int conn) {
  struct request r;
  if (recv_request(conn, &r) < 0) {
    send_err(conn, "daemon: bad request\n");
    _exit(UA_EXIT_FAIL);
  }
  /* UA_NO_PROXY is the real guard; this is the second one. A token right
   * after an option is that option's value, not a command. */
  for (int i = 1; i < r.argc; i++)
    if (!(i > 1 && r.argv[i - 1][0] == '-') && !strcmp(r.argv[i], "daemon")) {
      send_err(conn, "daemon: recursive call refused\n");
      _exit(UA_EXIT_FAIL);
    }
  char line[512] = "";
  for (int i = 1; i < r.argc; i++) {
    size_t len = strlen(line);
    snprintf(line + len, sizeof(line) - len, "%s%s", i > 1 ? " " : "",
             r.argv[i]);
  }
  ua_log("Running: %s", line);
  session(conn, &r);
  _exit(0);
}

static void daemonize(int foreground) {
  setenv("PATH",
         is_android() ? "/product/bin:/apex/com.android.runtime/bin:"
                        "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:"
                        "/vendor/bin:/vendor/xbin"
                      : "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:"
                        "/sbin:/bin",
         1);
  if (!foreground) {
    pid_t pid = fork();
    if (pid < 0)
      exit(UA_EXIT_FAIL);
    if (pid > 0)
      exit(UA_EXIT_OK);
    setsid();
    if ((pid = fork()) < 0)
      exit(UA_EXIT_FAIL);
    if (pid > 0)
      exit(UA_EXIT_OK);
  }
  if (chdir("/") < 0)
    exit(UA_EXIT_FAIL);
  /* Nothing a launcher left in the environment may steer a root process
   * that re-executes itself for every client */
  unsetenv("LD_PRELOAD");
  unsetenv("LD_LIBRARY_PATH");
  unsetenv("LD_AUDIT");
  unsetenv("IFS");

  if (!foreground) {
    char log[PATH_MAX], old[PATH_MAX + 8];
    struct stat st;
    ua_ws_path("Logs", "usb-arsenald.log", log, sizeof(log));
    if (stat(log, &st) == 0 && st.st_size > 2 * 1024 * 1024) {
      snprintf(old, sizeof(old), "%s.old", log);
      rename(log, old);
    }
    int null = open("/dev/null", O_RDONLY);
    int lfd = open(log, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (null >= 0)
      dup2(null, STDIN_FILENO);
    if (lfd >= 0) {
      dup2(lfd, STDOUT_FILENO);
      dup2(lfd, STDERR_FILENO);
    }
  }
  /* The one process that hands the controller back must not be the one
   * the OOM killer picks */
  write_file("/proc/self/oom_score_adj", "-1000");
}

static void pid_path(char *out, size_t size) {
  ua_ws_path(NULL, "usb-arsenald.pid", out, size);
}

static int daemon_stop(void) {
  char p[PATH_MAX];
  pid_path(p, sizeof(p));
  pid_t pid = ua_read_our_pid(p);
  if (pid <= 0) {
    ua_log("The daemon is not running");
    return UA_EXIT_OK;
  }
  kill(pid, SIGTERM);
  for (int i = 0; i < 50 && kill(pid, 0) == 0; i++)
    usleep(100000);
  if (kill(pid, 0) == 0) {
    ua_error("Daemon %d did not stop", (int)pid);
    return UA_EXIT_FAIL;
  }
  ua_log("Daemon stopped");
  return UA_EXIT_OK;
}

static int daemon_run(int foreground) {
  if (ua_daemon_probe()) {
    ua_error("The daemon is already running");
    return UA_EXIT_FAIL;
  }
  daemonize(foreground);
  ssize_t n = readlink("/proc/self/exe", self_path, sizeof(self_path) - 1);
  self_path[n > 0 ? n : 0] = '\0';

  char pidf[PATH_MAX], run[PATH_MAX];
  pid_path(pidf, sizeof(pidf));
  int pfd =
      open(pidf, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0644);
  if (pfd >= 0) {
    dprintf(pfd, "%d\n", (int)getpid());
    close(pfd);
  }

  struct sockaddr_un addr;
  socklen_t alen = make_addr(&addr);
  /* Run/ is what containers bind-mount: traversable by the group only */
  safe_strncpy(run, addr.sun_path, sizeof(run));
  *strrchr(run, '/') = '\0';
  mkdir(run, 0750);
  if (chown(run, 0, ua_gid) < 0 || chmod(run, 0750) < 0)
    ua_warn("Cannot set the owner of %s: %s", run, strerror(errno));
  /* The probe above found nobody listening, so a socket file left here is
   * from a daemon that died */
  unlink(addr.sun_path);

  int srv = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (srv < 0 || bind(srv, (struct sockaddr *)&addr, alen) < 0 ||
      chown(addr.sun_path, 0, ua_gid) < 0 || chmod(addr.sun_path, 0660) < 0 ||
      listen(srv, SOMAXCONN) < 0) {
    ua_error("Cannot listen on %s: %s", addr.sun_path, strerror(errno));
    unlink(pidf);
    return UA_EXIT_FAIL;
  }

  sigset_t set;
  sigemptyset(&set);
  sigaddset(&set, SIGTERM);
  sigaddset(&set, SIGINT);
  sigprocmask(SIG_BLOCK, &set, NULL);
  int sfd = signalfd(-1, &set, SFD_CLOEXEC);
  signal(SIGCHLD, SIG_IGN); /* sessions reap themselves */
  signal(SIGPIPE, SIG_IGN);
  signal(SIGHUP, SIG_IGN);

  ua_log("USB Arsenal daemon " UA_VERSION " listening on %s (pid %d, group "
         "%u)",
         addr.sun_path, (int)getpid(), (unsigned)ua_gid);
  struct pollfd pfd2[2] = {{srv, POLLIN, 0}, {sfd, POLLIN, 0}};
  for (;;) {
    if (poll(pfd2, 2, -1) < 0) {
      if (errno == EINTR)
        continue;
      break;
    }
    if (pfd2[1].revents & POLLIN)
      break;
    int conn = accept4(srv, NULL, NULL, SOCK_CLOEXEC);
    if (conn < 0)
      continue;
    if (!peer_authorized(conn)) {
      send_err(conn, "permission denied: only root or members of the "
                     "usb-arsenal group may connect\n");
      close(conn);
      continue;
    }
    pid_t h = fork();
    if (h == 0) {
      close(srv);
      close(sfd);
      /* The session waits for its own command */
      signal(SIGCHLD, SIG_DFL);
      sigprocmask(SIG_UNBLOCK, &set, NULL);
      handle_conn(conn);
    }
    close(conn);
  }
  /* The gadget lives in the kernel and outlives us; `usb-arsenal stop` is
   * what tears it down. Only our own files go. */
  ua_log("Daemon stopping");
  close(srv);
  unlink(addr.sun_path);
  unlink(pidf);
  return UA_EXIT_OK;
}

void ua_help_daemon(void) {
  printf("Usage: " UA_NAME " daemon [--foreground] [--gid GID]\n"
         "       " UA_NAME " daemon stop\n\n"
         "Run the root daemon that owns the USB controller. Every other\n"
         "copy of " UA_NAME " (an Android shell, a container, a chroot) then\n"
         "relays its commands here, so tethering, storage and HID work from\n"
         "anywhere the socket is reachable.\n\n"
         "Socket: <workspace>/Run/usb-arsenal.sock, mode 0660 root:GID.\n"
         "Workspace: " UA_WORKSPACE_ANDROID " on Android, " UA_WORKSPACE_LINUX
         "\n"
         "elsewhere. A container reaches the daemon by bind-mounting the\n"
         "host's Run/ directory onto its own " UA_WORKSPACE_LINUX "/Run.\n\n"
         "Options:\n"
         "  -f, --foreground  Stay attached and log to the terminal\n"
         "  -g, --gid GID     Group allowed besides root (default: the\n"
         "                    usb-arsenal group, else %d)\n"
         "  -h, --help        Show this help\n\n"
         "Examples:\n"
         "  " UA_NAME " daemon\n"
         "  " UA_NAME " daemon --foreground\n"
         "  " UA_NAME " daemon stop\n"
         "  # inside a container, for a non-root user:\n"
         "  groupadd -g %d usb-arsenal && usermod -aG usb-arsenal $USER\n",
         UA_DAEMON_GID, UA_DAEMON_GID);
}

int ua_cmd_daemon(int argc, char **argv) {
  static const struct option opts[] = {
      {"foreground", no_argument, NULL, 'f'},
      {"gid", required_argument, NULL, 'g'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  int fg = 0, c, gid_set = 0;
  if (ua_want_help(argc, argv)) {
    ua_help_daemon();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":fg:h", opts, NULL)) != -1) {
    char *end;
    switch (c) {
    case 'f':
      fg = 1;
      break;
    case 'g':
      ua_gid = (gid_t)strtoul(optarg, &end, 10);
      if (!*optarg || *end) {
        ua_usage_error("daemon", "--gid takes a number");
        return UA_EXIT_USAGE;
      }
      gid_set = 1;
      break;
    case 'h':
      ua_help_daemon();
      return UA_EXIT_OK;
    case ':':
      ua_usage_error("daemon", "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error("daemon", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  if (geteuid() != 0) {
    ua_error("The daemon must run as root");
    return UA_EXIT_FAIL;
  }
  if (optind < argc && !strcmp(argv[optind], "stop") && optind + 1 == argc)
    return daemon_stop();
  if (optind < argc) {
    ua_usage_error("daemon", "Unknown action '%s'", argv[optind]);
    return UA_EXIT_USAGE;
  }
  /* Bionic has no group database; on a plain Linux host use the real one */
  if (!gid_set) {
    struct group *gr = getgrnam("usb-arsenal");
    if (gr)
      ua_gid = gr->gr_gid;
  }
  return daemon_run(fg);
}

/* The client half: relay argv (and fds) to the daemon and replay what comes
 * back. -2 means no daemon is listening, so the caller runs locally. */
int ua_client_run(int argc, char **argv, const int *fds, int nfds) {
  struct sockaddr_un addr;
  socklen_t alen = make_addr(&addr);
  int s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (s < 0)
    return -2;
  if (connect(s, (struct sockaddr *)&addr, alen) < 0) {
    int err = errno;
    close(s);
    if (err == ECONNREFUSED || err == ENOENT)
      return -2;
    ua_error("Cannot reach the daemon at %s: %s", addr.sun_path, strerror(err));
    if (err == EACCES)
      ua_log("Only root and members of the usb-arsenal group (gid %d) may "
             "use it.",
             UA_DAEMON_GID);
    return UA_EXIT_FAIL;
  }

  uint8_t hdr[12];
  uint32_t v32 = htonl(UA_MAGIC);
  uint16_t v16 = htons(UA_PROTO);
  memcpy(hdr, &v32, 4);
  memcpy(hdr + 4, &v16, 2);
  v16 = htons((uint16_t)nfds);
  memcpy(hdr + 6, &v16, 2);
  v32 = htonl((uint32_t)argc);
  memcpy(hdr + 8, &v32, 4);
  /* A refusal may close the socket before the request is out; its verdict
   * is still queued for us to read, so EPIPE is not the end */
  int ok = ua_send_fds(s, hdr, sizeof(hdr), fds, nfds) == 0;
  for (int i = 0; ok && i < argc; i++) {
    uint32_t len = (uint32_t)strlen(argv[i]), nl = htonl(len);
    ok =
        write_all(s, &nl, 4) == 4 && write_all(s, argv[i], len) == (ssize_t)len;
  }

  for (;;) {
    uint8_t fh[5];
    uint32_t len;
    char buf[IOBUF];
    if (read_exact(s, fh, 5) < 0)
      break;
    memcpy(&len, fh + 1, 4);
    len = ntohl(len);
    if (fh[0] == MSG_EXIT) {
      uint32_t code = 1;
      if (len == 4 && read_exact(s, &code, 4) == 0)
        code = ntohl(code);
      close(s);
      return (int)code;
    }
    while (len) {
      uint32_t chunk = len < sizeof(buf) ? len : (uint32_t)sizeof(buf);
      if (read_exact(s, buf, chunk) < 0)
        goto lost;
      if (write_all(fh[0] == MSG_ERR ? STDERR_FILENO : STDOUT_FILENO, buf,
                    chunk) < 0) {
        /* our reader went away; keep draining so the session can finish */
      }
      len -= chunk;
    }
  }
lost:
  close(s);
  ua_error("Lost the connection to the daemon");
  return UA_EXIT_FAIL;
}
