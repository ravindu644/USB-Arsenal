/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Command dispatch, modelled on git: global options, then a command, then
 * that command's own action and options. See docs/usage.md.
 */

#include "usbarsenal.h"

static int cmd_status(int argc, char **argv);
static int cmd_stop(int argc, char **argv);
static int cmd_help(int argc, char **argv);
static int cmd_version(int argc, char **argv);
static void help_status(void);
static void help_stop(void);

struct command {
  const char *name;
  int (*fn)(int argc, char **argv);
  void (*help)(void);
  const char *summary;
};

static const struct command commands[] = {
    {"check", ua_cmd_check, ua_help_check, "Report what this kernel can do"},
    {"status", cmd_status, help_status, "Show the live gadget"},
    {"storage", ua_cmd_storage, ua_help_storage,
     "Mass storage: boot an ISO, share a disk image"},
    {"hid", ua_cmd_hid, ua_help_hid, "Keyboard and mouse nodes (/dev/hidgN)"},
    {"tether", ua_cmd_tether, ua_help_tether,
     "Give the host a network over USB"},
    {"stop", cmd_stop, help_stop, "Tear everything down, restore the owner"},
    {"identity", ua_cmd_identity, ua_help_identity,
     "Profiles: IDs, strings, serial, defaults"},
    {"daemon", ua_cmd_daemon, ua_help_daemon,
     "Run the root daemon other copies relay to"},
    {"help", cmd_help, NULL, "Show help for a command"},
    {"version", cmd_version, NULL, "Print the version"},
};

#define NCOMMANDS (sizeof(commands) / sizeof(commands[0]))

static const struct command *find_command(const char *name) {
  for (size_t i = 0; i < NCOMMANDS; i++)
    if (!strcmp(commands[i].name, name))
      return &commands[i];
  return NULL;
}

static void usage(void) {
  printf("Usage: " UA_NAME " [--udc NAME] [-v] <command> [<action>] "
         "[options]\n\n");
  printf("Turn this device into a composite USB gadget: bootable storage,\n"
         "USB tethering and HID, all at once over one cable.\n\n");
  printf("Commands:\n");
  for (size_t i = 0; i < NCOMMANDS; i++)
    printf("  %-9s %s\n", commands[i].name, commands[i].summary);
  printf("\nGlobal options:\n"
         "  --udc NAME     USB device controller to use (only needed when\n"
         "                 /sys/class/udc lists more than one)\n"
         "  -v, --verbose  Show debug output\n"
         "  -h, --help     Show this help\n"
         "  -V, --version  Print the version\n\n");
  printf("Examples:\n"
         "  " UA_NAME " check\n"
         "  " UA_NAME " storage start ubuntu.iso\n"
         "  " UA_NAME " hid start\n"
         "  " UA_NAME " tether start\n"
         "  " UA_NAME " stop\n\n"
         "Run '" UA_NAME " <command> --help' for a command's actions and "
         "options.\n");
}

/* The option getopt choked on: optopt for a short one (argv[optind - 1] is
 * the whole cluster then, "-vx"), the argument itself for a long one */
const char *ua_bad_option(char **argv) {
  static char shortopt[3] = "-?";
  if (optopt) {
    shortopt[1] = (char)optopt;
    return shortopt;
  }
  return argv[optind - 1];
}

void ua_usage_error(const char *cmd, const char *fmt, ...) {
  char msg[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(msg, sizeof(msg), fmt, ap);
  va_end(ap);
  ua_error("%s", msg);
  if (cmd)
    fprintf(stderr, "See '" UA_NAME " %s --help'.\n", cmd);
  else
    fprintf(stderr, "See '" UA_NAME " --help'.\n");
}

/* -h/--help anywhere after the command, so `storage start --help` works too */
int ua_want_help(int argc, char **argv) {
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--"))
      break;
    if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help"))
      return 1;
  }
  return 0;
}

static void help_status(void) {
  printf("Usage: " UA_NAME " status\n\n"
         "Show the backend, the controller and its state, and every live\n"
         "function: storage images, HID nodes, the tether interface.\n\n"
         "Example:\n"
         "  " UA_NAME " status\n");
}

static void help_stop(void) {
  printf("Usage: " UA_NAME " stop\n\n"
         "Tear down every function, unbind the gadget and give the controller\n"
         "back to whoever had it (on Android: the previous USB mode, so ADB\n"
         "and MTP return).\n\n"
         "Safe to run at any time, any number of times. It is also the\n"
         "recovery path: if " UA_NAME " was killed mid-way, run this.\n\n"
         "Example:\n"
         "  " UA_NAME " stop\n");
}

static int cmd_status(int argc, char **argv) {
  if (ua_want_help(argc, argv)) {
    help_status();
    return UA_EXIT_OK;
  }
  if (argc > 1) {
    ua_usage_error("status", "status takes no arguments");
    return UA_EXIT_USAGE;
  }
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;

  char p[PATH_MAX], state[64] = "unknown";
  snprintf(p, sizeof(p), "/sys/class/udc/%s/state", g.udc);
  read_file(p, state, sizeof(state));
  printf("Controller: %s (%s)\n", g.udc, state);
  if (!ua_gadget_live(&g)) {
    printf("Gadget:     not active\n");
    return UA_EXIT_OK;
  }
  printf("Gadget:     %s\n", g.path);
  ua_status_storage(&g);
  ua_status_hid(&g);
  ua_status_tether(&g);
  return UA_EXIT_OK;
}

static int cmd_stop(int argc, char **argv) {
  if (ua_want_help(argc, argv)) {
    help_stop();
    return UA_EXIT_OK;
  }
  if (argc > 1) {
    ua_usage_error("stop", "stop takes no arguments");
    return UA_EXIT_USAGE;
  }
  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  /* Nothing held: done, before anything that could fail (more than one UDC
   * and no --udc, say) turns a no-op into an error */
  char cfs[PATH_MAX];
  if (!ua_gadget_taken() &&
      (ua_find_configfs(cfs, sizeof(cfs)) < 0 || !ua_gadget_in_use(cfs))) {
    ua_tether_kill();
    ua_log("Nothing to stop");
    return UA_EXIT_OK;
  }
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_taken() && !ua_gadget_in_use(g.cfs)) {
    ua_tether_kill();
    ua_log("Nothing to stop");
    return UA_EXIT_OK;
  }
  /* The tether daemon owns NAT rules and a DHCP thread; let it clean those up
   * before the interface under it disappears */
  ua_tether_kill();
  ua_gadget_teardown(&g);
  ua_log("Stopped; %s handed back", g.udc);
  return UA_EXIT_OK;
}

static int cmd_help(int argc, char **argv) {
  if (argc < 2) {
    usage();
    return UA_EXIT_OK;
  }
  const struct command *c = find_command(argv[1]);
  if (!c) {
    ua_usage_error(NULL, "No such command: %s", argv[1]);
    return UA_EXIT_USAGE;
  }
  if (c->help)
    c->help();
  else
    usage();
  return UA_EXIT_OK;
}

static int cmd_version(int argc, char **argv) {
  (void)argc;
  (void)argv;
  char sock[PATH_MAX];
  ua_socket_path(sock, sizeof(sock));
  printf(UA_NAME " " UA_VERSION "\n");
  int d = ua_daemon_probe();
  if (d == 1)
    printf("mode: daemon (%s)\n", sock);
  else if (d < 0)
    printf("mode: daemon, but no access (%s; join the usb-arsenal group, "
           "gid %d)\n",
           sock, UA_DAEMON_GID);
  else
    printf("mode: direct\n");
  return UA_EXIT_OK;
}

/* Hand the whole command line to the daemon, globals included, so it runs
 * exactly as typed but on the host. -2 means no daemon: run it here. For
 * `storage start`, IMAGE is opened on this side and travels as fd 3. */
static int relay(int argc, char **argv, int cmd_index) {
  char *av[128];
  int fd = -1, n = 0;
  if (argc > 120)
    return -2;
  int img = 0;
  if (!strcmp(argv[cmd_index], "storage")) {
    /* parse() permutes in place; a scratch copy keeps argv intact */
    char *scratch[128];
    int sc = argc - cmd_index;
    for (int i = 0; i < sc; i++)
      scratch[i] = argv[cmd_index + i];
    scratch[sc] = NULL;
    img = ua_storage_client_fd(sc, scratch, &fd);
    if (img < 0)
      return UA_EXIT_FAIL;
    if (img > 0) {
      /* find that same string in the original argv */
      const char *image = scratch[img];
      img = 0;
      for (int i = cmd_index + 1; i < argc; i++)
        if (argv[i] == image)
          img = i;
    }
  }
  /* identity: generate only writes the user's own file, so it never goes to
   * the daemon. set sends its FILE open, plus the path it has here: the
   * daemon links to it when it sees that very file, else keeps a copy. */
  static char pathopt[PATH_MAX + 8];
  if (!strcmp(argv[cmd_index], "identity")) {
    int act = 0;
    for (int i = cmd_index + 1; i < argc; i++)
      if (argv[i][0] != '-') {
        if (!act) {
          act = i;
          if (!strcmp(argv[i], "generate"))
            return -2;
          if (strcmp(argv[i], "set"))
            break;
        } else {
          char real[PATH_MAX];
          if ((fd = open(argv[i], O_RDONLY | O_CLOEXEC)) < 0 ||
              !realpath(argv[i], real)) {
            ua_error("%s: %s", argv[i], strerror(errno));
            if (fd >= 0)
              close(fd);
            return UA_EXIT_FAIL;
          }
          snprintf(pathopt, sizeof(pathopt), "--path=%s", real);
          img = i;
          break;
        }
      }
  }
  for (int i = 1; i < argc; i++)
    av[n++] = (img && i == img) ? "--fd=3" : argv[i];
  if (pathopt[0])
    av[n++] = pathopt;
  av[n] = NULL;
  int r = ua_client_run(n, av, &fd, fd >= 0 ? 1 : 0);
  if (fd >= 0)
    close(fd);
  return r;
}

int main(int argc, char **argv) {
  static const struct option opts[] = {
      {"udc", required_argument, NULL, 'u'},
      {"verbose", no_argument, NULL, 'v'},
      {"help", no_argument, NULL, 'h'},
      {"version", no_argument, NULL, 'V'},
      {NULL, 0, NULL, 0},
  };
  int c;
  /* Taking the controller from Android stops adbd, and with it the shell
   * this may have been started from. Finishing the handoff matters more than
   * a dead terminal, so neither the hangup nor a write to it may kill us. */
  signal(SIGHUP, SIG_IGN);
  signal(SIGPIPE, SIG_IGN);
  /* Every command reports bad options itself, naming the command */
  opterr = 0;
  /* "+": stop at the command name, the rest belongs to the command */
  while ((c = getopt_long(argc, argv, "+:u:vhV", opts, NULL)) != -1) {
    switch (c) {
    case 'u':
      ua_udc_opt = optarg;
      break;
    case 'v':
      ua_verbose = 1;
      break;
    case 'h':
      usage();
      return UA_EXIT_OK;
    case 'V':
      return cmd_version(0, NULL);
    case ':':
      ua_usage_error(NULL, "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error(NULL, "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  if (optind >= argc) {
    usage();
    return UA_EXIT_OK;
  }

  const struct command *cmd = find_command(argv[optind]);
  if (!cmd) {
    ua_usage_error(NULL, "No such command: %s", argv[optind]);
    return UA_EXIT_USAGE;
  }
  /* Commands parse their own argv with getopt_long; optind 0 makes both
   * glibc and musl forget the "+" mode used above */
  int cmd_index = optind;
  const char *no_proxy = getenv("UA_NO_PROXY");
  if (cmd->fn != ua_cmd_daemon && cmd->fn != cmd_help &&
      cmd->fn != cmd_version && !(no_proxy && no_proxy[0] == '1') &&
      !ua_want_help(argc - cmd_index, argv + cmd_index)) {
    int r = relay(argc, argv, cmd_index);
    if (r != -2)
      return r;
  }
  int sub_argc = argc - cmd_index;
  char **sub_argv = argv + cmd_index;
  optind = 0;
  return cmd->fn(sub_argc, sub_argv);
}
