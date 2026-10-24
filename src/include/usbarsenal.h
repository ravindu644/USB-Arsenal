/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef USBARSENAL_H
#define USBARSENAL_H

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <getopt.h>
#include <limits.h>
#include <net/if.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/mount.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define UA_NAME "usb-arsenal"
#define UA_VERSION "1.2.0"

/* Our own gadget directory, and the instance name every function we create
 * carries */
#define UA_WORKSPACE_ANDROID "/data/local/USB-Arsenal"
#define UA_WORKSPACE_LINUX "/var/lib/USB-Arsenal"

#define UA_GADGET "usb_arsenal"
#define UA_CONFIG "c.1"

/* The functions we create, by their configfs names. The "ua" instance marks
 * them as ours (gadget.c is_ours). */
#define UA_F_STORAGE "mass_storage.ua"
#define UA_F_NCM "ncm.ua"
#define UA_F_RNDIS "rndis.ua"
#define UA_F_KBD "hid.ua_kbd"
#define UA_F_MOUSE "hid.ua_mouse"

enum ua_fn { UA_FN_STORAGE, UA_FN_TETHER, UA_FN_HID, UA_FN_ALL = -1 };

/* Hardware knobs. Real controllers and hosts are slower than the spec says;
 * raise these if a device needs it. */
#define UA_HANDOFF_TIMEOUT_MS 5000  /* Android dropping/raising its gadget */
#define UA_ENUM_TIMEOUT_MS 3000     /* host reaching "configured" after bind */
#define UA_RESTORE_TIMEOUT_MS 10000 /* Android's mode back, adbd included */
#define UA_PULLUP_WAIT_MS 1000      /* enumeration starting after bind */

/* Exit codes, the way git and most CLIs split them */
#define UA_EXIT_OK 0
#define UA_EXIT_FAIL 1
#define UA_EXIT_USAGE 2

#define C_RESET "\033[0m"
#define C_RED "\033[1;31m"
#define C_GREEN "\033[1;32m"
#define C_YELLOW "\033[1;33m"
#define C_BLUE "\033[1;34m"
#define C_CYAN "\033[1;36m"
#define C_DIM "\033[2m"
#define C_BOLD "\033[1m"

/* utils.c */

extern int ua_verbose;

void ua_log_internal(const char *prefix, const char *color, int is_err,
                     const char *fmt, ...)
    __attribute__((format(printf, 4, 5)));
#define ua_log(fmt, ...) ua_log_internal("+", C_GREEN, 0, fmt, ##__VA_ARGS__)
#define ua_warn(fmt, ...) ua_log_internal("!", C_YELLOW, 1, fmt, ##__VA_ARGS__)
#define ua_error(fmt, ...) ua_log_internal("-", C_RED, 1, fmt, ##__VA_ARGS__)
/* Tagged lines only reach the terminal with -v */
#define ua_debug(fmt, ...)                                                     \
  ua_log_internal("*", C_DIM, 0, "[DEBUG] " fmt, ##__VA_ARGS__)

void safe_strncpy(char *dst, const char *src, size_t size);
void ua_json_str(const char *key, const char *val, int *first);
void ua_json_int(const char *key, long long val, int *first);
int mkdir_p(const char *path, mode_t mode);
ssize_t write_all(int fd, const void *buf, size_t count);
int write_file(const char *path, const char *content);
int write_attr(const char *dir, const char *attr, const char *val);
int write_attr_buf(const char *dir, const char *attr, const void *buf,
                   size_t len);
int read_file(const char *path, char *buf, size_t size);
int run_command_quiet(char *const argv[]);
int run_command_log(char *const argv[]);
int run_capture(char *const argv[], char *out, size_t size);
int ds_thread_create(pthread_t *tid, void *(*fn)(void *), void *arg);
int is_android(void);
int ua_getprop(const char *name, char *out, size_t size);
int ua_setprop(const char *name, const char *value);
int ua_wait(int (*get)(const char *, char *, size_t), const char *key,
            const char *want, int timeout_ms);
pid_t ua_read_our_pid(const char *pidfile);
void ua_device_seed(char *out, size_t size);
#define UA_MAX_FDS 8
int ua_send_fds(int sock, const void *data, size_t len, const int *fds,
                int count);
ssize_t ua_recv_fds(int sock, void *data, size_t len, int *fds, int max,
                    int *count);
const char *ua_workspace(void);
void ua_ws_path(const char *sub, const char *leaf, char *out, size_t size);

/* gadget.c */

struct ua_gadget {
  char cfs[PATH_MAX];    /* .../usb_gadget */
  char udc[64];          /* controller name from /sys/class/udc */
  char path[PATH_MAX];   /* our gadget */
  char config[PATH_MAX]; /* the config our functions are linked into */
  int android_gadget; /* Android's g1 owns this UDC when we are not using it */
};

extern const char *ua_udc_opt; /* --udc */

int ua_find_configfs(char *out, size_t size);
int ua_list_udcs(char names[][64], int max);
int ua_pick_udc(char *out, size_t size);
int ua_gadget_open(struct ua_gadget *g);
int ua_gadget_taken(void);
int ua_gadget_in_use(const char *cfs);
int ua_gadget_live(const struct ua_gadget *g);
int ua_gadget_has(const struct ua_gadget *g, const char *func);
int ua_gadget_begin(struct ua_gadget *g);
int ua_gadget_add(struct ua_gadget *g, const char *func, char *dir,
                  size_t size);
void ua_gadget_unlink(struct ua_gadget *g, const char *func);
int ua_gadget_remove(struct ua_gadget *g, const char *func);
int ua_gadget_commit(struct ua_gadget *g);
void ua_gadget_teardown(struct ua_gadget *g);
/* The critical section begin opens and commit closes; apply swaps a function
 * inside another command's begin and closes it with ua_crit_leave */
void ua_crit_enter(void);
void ua_crit_leave(void);
void ua_boot_marker(char *out, size_t size);
int ua_fn_linked(const struct ua_gadget *g, enum ua_fn fn);
int ua_gadget_func_dir(const struct ua_gadget *g, const char *func, char *dir,
                       size_t size);

/* config.c: one key table drives loading, validation, generate and show; the
 * same machinery serves the profile (here) and the setup (setup.c) */

enum ua_kind {
  K_HEX16,
  K_UINT,
  K_STR,
  K_MAC,
  K_CHOICE,
  K_CIDR,
  K_DNS,
  K_OCTAL,
  K_LUN /* MODE:PATH, or empty */
};

struct ua_key {
  const char *name;
  enum ua_kind kind;
  size_t off, size; /* field in the struct; size for strings */
  unsigned max;     /* K_UINT, K_OCTAL upper bound; K_STR max length */
  const char *choices, *comment;
};

int ua_key_set(void *base, const struct ua_key *k, const char *val);
void ua_key_get(const void *base, const struct ua_key *k, char *out,
                size_t size);
int ua_table_load(const char *path, const struct ua_key *keys, size_t nkeys,
                  void *base);
int ua_write_atomic(const char *path, mode_t mode,
                    void (*body)(FILE *, const void *), const void *arg);

struct ua_config {
  char source[PATH_MAX]; /* the profile in force, "" = built-in */
  int copied;            /* source is a copy kept in the workspace */
  unsigned id_vendor, id_product, bcd_device, bcd_usb, max_power;
  char manufacturer[127], product[127], serial[127], configuration[127];
  char storage_inquiry[29];
  char tether_dev_mac[18], tether_host_mac[18];
  char storage_mode[8], tether_function[8];
  char tether_address[32], tether_dns[128], tether_upstream[IFNAMSIZ];
  unsigned hid_perm, hid_group;
};

void ua_config_active(struct ua_config *c);
/* One or more IPv4 addresses, comma-separated: tether_dns and tether --dns */
int ua_valid_dns(const char *v);
int ua_cmd_identity(int argc, char **argv);
void ua_help_identity(void);

/* setup.c: what each function starts with (Gadget/setup.conf, the draft) and
 * what the last successful start used (Gadget/running.conf). Same keys. */

#define UA_LUNS 8
#define UA_LUN_SPEC 512 /* "mode:path" */

struct ua_setup {
  char udc[64];
  char lun[UA_LUNS][UA_LUN_SPEC];
  char tether_function[8], tether_gateway[256], tether_gateway_iface[IFNAMSIZ];
  unsigned tether_net;
  char tether_address[32], tether_dns[128], tether_upstream[IFNAMSIZ];
  unsigned hid_interval;
};

void ua_setup_load(struct ua_setup *s);
void ua_running_load(struct ua_setup *s);
int ua_setup_save(const struct ua_setup *s);
int ua_setup_set(struct ua_setup *s, const char *assignment);
/* Running takes fn's keys from the setup; for storage only the LUNs in mask */
int ua_running_take(enum ua_fn fn, unsigned mask);
int ua_running_clear(enum ua_fn fn);
int ua_running_clear_lun(int lun);
int ua_setup_pending(enum ua_fn fn, const struct ua_setup *setup,
                     const struct ua_setup *running);
void ua_setup_json(const struct ua_gadget *g, int live, int *first);
int ua_cmd_setup(int argc, char **argv);
void ua_help_setup(void);

/* check.c */

#define UA_NEED_STORAGE (1 << 0)
#define UA_NEED_HID (1 << 1)
#define UA_NEED_NET (1 << 2)

int ua_ncm_unsafe(void);
int ua_probe_function(const struct ua_gadget *g, const char *type,
                      const char *attr);
int ua_check_requirements(struct ua_gadget *g, int need);
int ua_cmd_check(int argc, char **argv);
void ua_help_check(void);

/* main.c */

void ua_usage_error(const char *cmd, const char *fmt, ...)
    __attribute__((format(printf, 2, 3)));
int ua_want_help(int argc, char **argv);
const char *ua_bad_option(char **argv);

/* daemon.c */

/* Members of this group may use the daemon besides root. Outside Android's
 * AID ranges; containers create it with groupadd -g 3460 usb-arsenal. */
#define UA_DAEMON_GID 3460

int ua_cmd_daemon(int argc, char **argv);
void ua_help_daemon(void);
int ua_daemon_probe(void);
void ua_socket_path(char *out, size_t size);
int ua_client_run(int argc, char **argv, const int *fds, int nfds);

/* gadgets/storage.c */

int ua_cmd_storage(int argc, char **argv);
int ua_storage_client_fds(int argc, char **argv, int *fds, int *idx);
void ua_help_storage(void);
void ua_status_storage(const struct ua_gadget *g, int json);

/* gadgets/hid.c */

int ua_cmd_hid(int argc, char **argv);
void ua_help_hid(void);
void ua_status_hid(const struct ua_gadget *g, int json);
void ua_hid_reapply(const struct ua_gadget *g);

/* gadgets/tether.c */

int ua_cmd_tether(int argc, char **argv);
void ua_help_tether(void);
void ua_status_tether(const struct ua_gadget *g, int json);
void ua_tether_kill(void);

#endif /* USBARSENAL_H */
