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

/* status: 1 ok, 0 missing, -1 works with caveats */
static void line(int status, int must, const char *name, const char *desc) {
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
  printf("Usage: " UA_NAME " check\n\n"
         "Probe the running kernel for everything USB Arsenal needs, the way\n"
         "it will actually be used: each gadget function is created and\n"
         "removed again, no config dump is read.\n\n"
         "MUST HAVE lines block every command. A missing FUNCTIONS line only\n"
         "blocks the command that needs it.\n\n"
         "Examples:\n"
         "  " UA_NAME " check\n"
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
  if (argc > 1) {
    ua_usage_error("check", "check takes no arguments");
    return UA_EXIT_USAGE;
  }

  int missing = 0, root = geteuid() == 0;
  char cfs[PATH_MAX] = "", udc[64] = "", desc[PATH_MAX + 64];
  struct ua_gadget g;

  printf(C_BOLD "USB Arsenal " UA_VERSION " - checking this system" C_RESET
                "\n\n[MUST HAVE]\n");
  line(root, 1, "Root privileges", "Run as root (su)");
  missing += !root;

  int have_cfs = ua_find_configfs(cfs, sizeof(cfs)) == 0;
  snprintf(desc, sizeof(desc), "configfs USB gadget%s%s%s",
           have_cfs ? " (" : "", cfs, have_cfs ? ")" : "");
  line(have_cfs, 1, desc,
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
  line(have_udc, 1, desc,
       nudc > 1 ? "More than one controller; pick one with --udc"
       : nudc   ? "No such controller as the one --udc names"
                : "No peripheral-mode controller in /sys/class/udc (on a "
                  "Raspberry Pi: dtoverlay=dwc2)");
  missing += !have_udc;

  int have_backend = have_cfs && have_udc && root && ua_gadget_open(&g) == 0;
  line(have_backend, 1, "Own USB gadget",
       missing ? "Needs the lines above first"
               : "Could not create a gadget; see the errors above");
  missing += !have_backend;

  printf("\n[FUNCTIONS]\n");
  if (have_backend) {
    line(ua_probe_function(&g, "mass_storage", NULL), 0, "mass_storage",
         "CONFIG_USB_CONFIGFS_MASS_STORAGE; needed by 'storage'");
    line(ua_probe_function(&g, "mass_storage", "lun.0/cdrom"), 0,
         "CD-ROM emulation (mass_storage)",
         "Without lun.0/cdrom, non-hybrid ISOs (Windows) cannot boot");
    line(ua_probe_function(&g, "hid", NULL), 0, "hid",
         "CONFIG_USB_CONFIGFS_F_HID; needed by 'hid'");
    if (ua_ncm_unsafe())
      line(-1, 0, "ncm (unsafe on this Samsung kernel; tether uses rndis)",
           "Another NCM instance would make f_ncm destroy android0 and the "
           "kernel BUG. The kernel fix (docs/kernel-requirements.md) adds "
           "android0/ncm_instances, and NCM is used again once it is "
           "there.");
    else
      line(ua_probe_function(&g, "ncm", NULL), 0, "ncm",
           "CONFIG_USB_CONFIGFS_NCM; preferred by 'tether'");
    line(ua_probe_function(&g, "rndis", NULL), 0, "rndis",
         "CONFIG_USB_CONFIGFS_RNDIS; 'tether --rndis' fallback for Windows "
         "7 and older hosts");
    line(ds_ipt4_nat_available(), 0, "NAT for tethering",
         "The iptables nat table and MASQUERADE target "
         "(CONFIG_IP_NF_NAT, CONFIG_IP_NF_TARGET_MASQUERADE)");
    line(ds_ipt6_available(), 0, "IPv6 NAT for tethering",
         "The ip6tables nat table and MASQUERADE target "
         "(CONFIG_IP6_NF_NAT, CONFIG_IP6_NF_TARGET_MASQUERADE); without "
         "them the host gets IPv4 only");
  } else {
    printf("  Skipped: needs every MUST HAVE line first.\n");
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
