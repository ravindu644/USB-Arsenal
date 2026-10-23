/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Capability probing, after Droidspaces' check.c: ask the running kernel by
 * doing the real thing (create the function, remove it again) rather than
 * reading a config dump that may not match what is loaded.
 */

#include "net/net.h"
#include "usbarsenal.h"

#define PROBE_GADGET "ua_probe"

/* Not a "ua" instance: a probe left behind by a crash must never look like
 * one of ours and get linked into a gadget.
 * 1 if the kernel can create a <type> function (and, if attr is given, that
 * function has that attribute). Probes inside the gadget we would use, or a
 * throwaway one when ours does not exist yet. */
/* Samsung's f_ncm (CONFIG_USB_ANDROID_SAMSUNG_COMPOSITE, every 4.x tree we
 * have) hangs a terminal_version attribute on the shared android0 device
 * for each NCM instance. A second instance fails to add it, and the error
 * path calls device_destroy() on android0 itself (f_ncm.c
 * create_terminal_attribute). The next NCM instance, or Android's own
 * gadget, then touches a freed device and the kernel BUGs. The attribute
 * existing means an instance is already there: never make another one,
 * not even to probe. */
int ua_ncm_unsafe(void) {
  /* A kernel carrying the fix (an instance count instead of per-instance
   * create and destroy) says so with android0/ncm_instances. Without it,
   * Samsung's extras present means the bug is present. Asked by looking,
   * never by trying: trying is what panics. */
  return access("/sys/class/android_usb/android0/terminal_version", F_OK) ==
             0 &&
         access("/sys/class/android_usb/android0/ncm_instances", F_OK) != 0;
}

int ua_probe_function(const struct ua_gadget *g, const char *type,
                      const char *attr) {
  char root[PATH_MAX], fdir[PATH_MAX], a[PATH_MAX];
  int temp = 0, ok = 0;
  if (!strcmp(type, "ncm") && ua_ncm_unsafe())
    return 0;

  if (access(g->path, F_OK) == 0) {
    safe_strncpy(root, g->path, sizeof(root));
  } else if (access("/sys/class/android_usb", F_OK) == 0) {
    return 0; /* removing a probe gadget here destroys android0 */
  } else {
    snprintf(root, sizeof(root), "%s/" PROBE_GADGET, g->cfs);
    if (mkdir(root, 0755) < 0 && errno != EEXIST)
      return 0;
    temp = 1;
  }
  if (snprintf(fdir, sizeof(fdir), "%s/functions/%s.arsenal_probe", root,
               type) >= (int)sizeof(fdir))
    goto out;
  /* The kernel loads usb_f_<type> on demand here, so a modular build counts */
  if (mkdir(fdir, 0755) == 0) {
    ok = 1;
    if (attr) {
      snprintf(a, sizeof(a), "%s/%s", fdir, attr);
      ok = access(a, F_OK) == 0;
    }
    rmdir(fdir);
  }
out:
  if (temp)
    rmdir(root);
  return ok;
}

static int need_root(void) {
  if (geteuid() == 0)
    return 0;
  ua_error("Must be run as root");
  return -1;
}

/* The quick gate in front of every action. Fails closed, and names the check
 * report for the details. */
int ua_check_requirements(struct ua_gadget *g, int need) {
  if (need_root() < 0)
    return -1;
  int missing = 0;
  if ((need & UA_NEED_STORAGE) && !ua_probe_function(g, "mass_storage", NULL)) {
    ua_error("This kernel has no mass_storage gadget function "
             "(CONFIG_USB_CONFIGFS_MASS_STORAGE)");
    missing++;
  }
  if ((need & UA_NEED_HID) && !ua_probe_function(g, "hid", NULL)) {
    ua_error("This kernel has no hid gadget function "
             "(CONFIG_USB_CONFIGFS_F_HID)");
    missing++;
  }
  if ((need & UA_NEED_NET) && !ua_probe_function(g, "ncm", NULL) &&
      !ua_probe_function(g, "rndis", NULL)) {
    ua_error("This kernel has neither the ncm nor the rndis gadget function "
             "(CONFIG_USB_CONFIGFS_NCM / _RNDIS)");
    missing++;
  }
  if (missing)
    ua_log("Run '" UA_NAME " check' for the full report.");
  return missing ? -1 : 0;
}

/* check --json: one flat object, one int per probe, for an app to read */
static int json_mode, json_first = 1;

/* status: 1 ok, 0 missing, -1 works with caveats */
static void line(const char *key, int status, int must, const char *name,
                 const char *desc) {
  if (json_mode) {
    ua_json_int(key, status, &json_first);
    return;
  }
  const char *color = status > 0   ? C_GREEN
                      : status < 0 ? C_YELLOW
                      : must       ? C_RED
                                   : C_YELLOW;
  const char *sym = status > 0 ? "✓" : status < 0 ? "!" : "✗";
  printf("  [%s%s" C_RESET "] %s\n", color, sym, name);
  if (status <= 0 && desc)
    printf("      " C_DIM "%s" C_RESET "\n", desc);
}

void ua_help_check(void) {
  printf("Usage: " UA_NAME " check [--json]\n\n"
         "Probe the running kernel for everything USB Arsenal needs, the way\n"
         "it will actually be used: each gadget function is created and\n"
         "removed again, no config dump is read.\n\n"
         "MUST HAVE lines block every command. A missing FUNCTIONS line only\n"
         "blocks the command that needs it.\n\n"
         "  --json    One JSON object instead: each probe as 1 (ok), 0\n"
         "            (missing) or -1 (works with caveats), plus version,\n"
         "            udc_name and requirements_met. Same exit code.\n\n"
         "Examples:\n"
         "  " UA_NAME " check\n"
         "  " UA_NAME " check --json\n"
         "  " UA_NAME " --udc fe980000.usb check\n");
}

static void help_check_hint(void) {
  printf("\nRun '" UA_NAME " check' as root for an accurate report.\n");
}

int ua_cmd_check(int argc, char **argv) {
  if (ua_want_help(argc, argv)) {
    ua_help_check();
    return UA_EXIT_OK;
  }
  json_mode = argc == 2 && !strcmp(argv[1], "--json");
  if (argc > 1 && !json_mode) {
    ua_usage_error("check", "check takes no arguments but --json");
    return UA_EXIT_USAGE;
  }

  int missing = 0, root = geteuid() == 0;
  char cfs[PATH_MAX] = "", udc[64] = "", desc[PATH_MAX + 64];
  struct ua_gadget g;

  if (json_mode)
    printf("{");
  else
    printf(C_BOLD "USB Arsenal " UA_VERSION " - checking this system" C_RESET
                  "\n\n[MUST HAVE]\n");
  line("root", root, 1, "Root privileges", "Run as root (su)");
  missing += !root;

  int have_cfs = ua_find_configfs(cfs, sizeof(cfs)) == 0;
  snprintf(desc, sizeof(desc), "configfs USB gadget%s%s%s",
           have_cfs ? " (" : "", cfs, have_cfs ? ")" : "");
  line("configfs", have_cfs, 1, desc,
       "CONFIG_USB_CONFIGFS. Stock 3.18 kernels ship the legacy android "
       "gadget instead and need a custom kernel.");
  missing += !have_cfs;

  /* Listed here rather than through ua_pick_udc(), whose errors would land
   * on stderr in the middle of this report */
  char names[16][64], list[512] = "";
  int nudc = ua_list_udcs(names, 16), have_udc = 0;
  for (int i = 0; i < nudc; i++) {
    size_t len = strlen(list);
    snprintf(list + len, sizeof(list) - len, "%s%s", i ? ", " : "", names[i]);
    if (ua_udc_opt ? !strcmp(names[i], ua_udc_opt) : nudc == 1) {
      safe_strncpy(udc, names[i], sizeof(udc));
      have_udc = 1;
    }
  }
  snprintf(desc, sizeof(desc), "USB device controller%s%s%s", nudc ? " (" : "",
           have_udc ? udc : list, nudc ? ")" : "");
  /* A dual-role controller leaves /sys/class/udc while its port is a host:
   * dwc3 removes its gadget on the switch (__dwc3_set_mode) */
  char why[PATH_MAX];
  if (ua_udc_opt)
    snprintf(why, sizeof(why),
             "%s is not in /sys/class/udc now: a typo, or a dual-role port in "
             "host mode (OTG), which lists none until it is a device again",
             ua_udc_opt);
  else
    snprintf(why, sizeof(why), "%s",
             nudc > 1 ? "More than one controller; pick one with --udc"
                      : "No peripheral-mode controller in /sys/class/udc now. "
                        "A dual-role port in host mode (OTG) lists none, and "
                        "dummy_udc.* counts only when --udc names it; on a "
                        "Raspberry Pi: dtoverlay=dwc2");
  line("udc", have_udc, 1, desc, why);
  missing += !have_udc;

  int have_backend = have_cfs && have_udc && root && ua_gadget_open(&g) == 0;
  line("gadget", have_backend, 1, "Own USB gadget",
       missing ? "Needs the lines above first"
               : "Could not create a gadget; see the errors above");
  missing += !have_backend;

  if (!json_mode)
    printf("\n[FUNCTIONS]\n");
  if (have_backend) {
    line("mass_storage", ua_probe_function(&g, "mass_storage", NULL), 0,
         "mass_storage",
         "CONFIG_USB_CONFIGFS_MASS_STORAGE; needed by 'storage'");
    line("cdrom", ua_probe_function(&g, "mass_storage", "lun.0/cdrom"), 0,
         "CD-ROM emulation (mass_storage)",
         "Without lun.0/cdrom, non-hybrid ISOs (Windows) cannot boot");
    int hid = ua_probe_function(&g, "hid", NULL);
    line("hid", hid, 0, "hid", "CONFIG_USB_CONFIGFS_F_HID; needed by 'hid'");
    /* Known before HID runs, so a client never offers an interval the
     * kernel would ignore */
    line("hid_interval", hid > 0 && ua_probe_function(&g, "hid", "interval"), 0,
         "HID poll interval (f_hid)",
         "Stock f_hid polls the keyboard every 1 ms; the f_hid patch in "
         "kernel-patches/ makes 'hid --interval' work");
    if (ua_ncm_unsafe())
      line("ncm", -1, 0,
           "ncm (unsafe on this Samsung kernel; tether uses rndis)",
           "Another NCM instance would make f_ncm destroy android0 and the "
           "kernel BUG. The kernel fix (docs/kernel-requirements.md) adds "
           "android0/ncm_instances, and NCM is used again once it is "
           "there.");
    else
      line("ncm", ua_probe_function(&g, "ncm", NULL), 0, "ncm",
           "CONFIG_USB_CONFIGFS_NCM; preferred by 'tether'");
    line("rndis", ua_probe_function(&g, "rndis", NULL), 0, "rndis",
         "CONFIG_USB_CONFIGFS_RNDIS; 'tether --rndis' fallback for Windows "
         "7 and older hosts");
    line("nat", ds_ipt4_nat_available(), 0, "NAT for tethering",
         "The iptables nat table and MASQUERADE target "
         "(CONFIG_IP_NF_NAT, CONFIG_IP_NF_TARGET_MASQUERADE)");
    line("nat6", ds_ipt6_available(), 0, "IPv6 NAT for tethering",
         "The ip6tables nat table and MASQUERADE target "
         "(CONFIG_IP6_NF_NAT, CONFIG_IP6_NF_TARGET_MASQUERADE); without "
         "them the host gets IPv4 only");
  } else if (json_mode) {
    /* Every key always present, so a reader never has to guess */
    static const char *const fkeys[] = {"mass_storage", "cdrom", "hid",
                                        "hid_interval", "ncm",   "rndis",
                                        "nat",          "nat6"};
    for (size_t i = 0; i < sizeof(fkeys) / sizeof(fkeys[0]); i++)
      ua_json_int(fkeys[i], 0, &json_first);
  } else {
    printf("  Skipped: needs every MUST HAVE line first.\n");
  }

  if (json_mode) {
    ua_json_str("version", UA_VERSION, &json_first);
    ua_json_str("udc_name", udc, &json_first);
    ua_json_int("requirements_met", !missing, &json_first);
    printf("}\n");
    return missing ? UA_EXIT_FAIL : UA_EXIT_OK;
  }

  printf("\n");
  if (missing)
    printf("  %d required feature(s) missing - USB Arsenal will not work\n",
           missing);
  else
    printf("  All required features found\n");
  if (!root)
    help_check_hint();
  return missing ? UA_EXIT_FAIL : UA_EXIT_OK;
}
