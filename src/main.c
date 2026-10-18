/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Command dispatch, modelled on git: global options, then a command, then
 * that command's own action and options. See docs/cli.md.
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
  printf("Backend:    %s\n",
         g.piggyback ? "piggyback (inside Android's g1)" : "own gadget");
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
  char cfs[PATH_MAX], own[PATH_MAX + 16];
  if (!ua_gadget_taken() && (ua_find_configfs(cfs, sizeof(cfs)) < 0 ||
                             (snprintf(own, sizeof(own), "%s/" UA_GADGET, cfs),
                              access(own, F_OK) != 0))) {
    ua_tether_kill();
    ua_log("Nothing to stop");
    return UA_EXIT_OK;
  }
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_taken() && (g.piggyback || access(g.path, F_OK) != 0)) {
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
  printf(UA_NAME " " UA_VERSION "\n");
  return UA_EXIT_OK;
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
  int sub_argc = argc - optind;
  char **sub_argv = argv + optind;
  optind = 0;
  return cmd->fn(sub_argc, sub_argv);
}
