/*
 * USB Arsenal - lifted from Droidspaces (src/net/network.c) by the same author
 *
 * Acting inside another network namespace from the host: the gateway tether
 * mode plugs a cable into a container (OpenWrt) the way Droidspaces' gateway
 * mode does, and needs to look into that container to see the cable is
 * still alive.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "net.h"
#include "usbarsenal.h"
#include <sched.h>

/* Open a container's network namespace. Fails closed when it turns out to
 * be our own: a recycled pid, or an init that died, would otherwise have the
 * host plug the container's cable into itself. */
int ds_open_container_netns(const char *netns_path) {
  int fd = open(netns_path, O_RDONLY | O_CLOEXEC);
  if (fd < 0)
    return -1;
  struct stat target, self;
  if (fstat(fd, &target) == 0 && stat("/proc/self/ns/net", &self) == 0 &&
      target.st_ino == self.st_ino && target.st_dev == self.st_dev) {
    ua_warn("[NET] %s is our own network namespace - refusing to use it",
            netns_path);
    close(fd);
    errno = EPERM;
    return -1;
  }
  return fd;
}

/* Run op(ctx, ifname) inside the namespace at netns_path, then come back.
 * Returns op's result, or a negative errno if we never got in. */
static int in_netns(const char *netns_path, const char *ifname,
                    int (*op)(ds_nl_ctx_t *, const char *)) {
  int self_fd = open("/proc/self/ns/net", O_RDONLY | O_CLOEXEC);
  if (self_fd < 0)
    return -errno;
  int target_fd = ds_open_container_netns(netns_path);
  if (target_fd < 0) {
    int e = -errno;
    close(self_fd);
    return e;
  }
  int ret;
  if (setns(target_fd, CLONE_NEWNET) < 0) {
    ret = -errno;
  } else {
    ds_nl_ctx_t *ctx = ds_nl_open();
    ret = ctx ? op(ctx, ifname) : -errno;
    if (ctx)
      ds_nl_close(ctx);
    /* Staying in the container's namespace would make every later netlink
     * call act on the container */
    if (setns(self_fd, CLONE_NEWNET) < 0) {
      ua_error("[NET] Cannot return to our network namespace: %s",
               strerror(errno));
      _exit(UA_EXIT_FAIL);
    }
  }
  close(target_fd);
  close(self_fd);
  return ret;
}

int ds_netns_link_up(const char *netns_path, const char *ifname) {
  return in_netns(netns_path, ifname, ds_nl_link_up);
}

static int link_exists(ds_nl_ctx_t *ctx, const char *ifname) {
  return ds_nl_link_exists(ctx, ifname) ? 1 : 0;
}

/* 1 if ifname exists inside the namespace. Any error reads as "not there",
 * so the caller rebuilds the cable: the safe default. */
int ds_netns_has_link(const char *netns_path, const char *ifname) {
  return in_netns(netns_path, ifname, link_exists) == 1;
}

/* Droidspaces keeps a container's init pid in Pids/<name>.pid, with spaces
 * in the name turned into dashes */
static pid_t droidspaces_pidfile(const char *name) {
  char p[PATH_MAX], clean[256], buf[32];
  size_t i;
  for (i = 0; name[i] && i < sizeof(clean) - 1; i++)
    clean[i] = name[i] == ' ' ? '-' : name[i];
  clean[i] = '\0';
  snprintf(p, sizeof(p), "%s/Pids/%s.pid",
           is_android() ? "/data/local/Droidspaces" : "/var/lib/Droidspaces",
           clean);
  if (read_file(p, buf, sizeof(buf)) <= 0)
    return 0;
  return (pid_t)strtol(buf, NULL, 10);
}

/* The network namespace of a container, given by Droidspaces name, by pid,
 * or as a path (docker, lxc: anything with a pid or a netns file). */
int ua_container_netns(const char *name, pid_t pid, const char *path, char *out,
                       size_t size) {
  if (path && path[0]) {
    safe_strncpy(out, path, size);
    return access(out, R_OK) == 0 ? 0 : -1;
  }
  if (name && name[0]) {
    pid = droidspaces_pidfile(name);
    if (pid <= 0 || kill(pid, 0) < 0) {
      /* The CLI also covers a pid file that went stale: it rescans */
      char buf[32], n[256];
      safe_strncpy(n, name, sizeof(n));
      char *argv[] = {"droidspaces", "--name", n, "pid", NULL};
      pid = run_capture(argv, buf, sizeof(buf)) == 0
                ? (pid_t)strtol(buf, NULL, 10)
                : 0;
    }
  }
  if (pid <= 0 || kill(pid, 0) < 0)
    return -1;
  snprintf(out, size, "/proc/%d/ns/net", (int)pid);
  return 0;
}
