# USB Arsenal - build system, modelled on Droidspaces
# Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
# SPDX-License-Identifier: GPL-3.0-or-later

BINARY_NAME = usb-arsenal
SRC_DIR     = src
OUT_DIR     = output

CC ?= cc

# UA_VERSION in usbarsenal.h is the one place the version lives; `make bump`
# rewrites it. versionCode follows Droidspaces: major*1000 + minor*100 + patch*10.
VERSION := $(shell grep "define UA_VERSION" $(SRC_DIR)/include/usbarsenal.h | awk '{print $$3}' | tr -d '"')
VERSION_CODE := $(shell echo $(VERSION) | awk -F. '{print $$1*1000 + $$2*100 + $$3*10}')
NPROC   := $(shell nproc 2>/dev/null || echo 4)

V ?= 0
ifeq ($(V),1)
  Q      =
  msg_cc =
  msg_ld =
else
  Q      = @
  msg_cc = @printf "  CC      %s\n" $<
  msg_ld = @printf "  LD      %s\n" $@
endif

# Every source listed by hand: a stray .c in the tree must not get linked in
SRCS = $(SRC_DIR)/main.c \
       $(SRC_DIR)/utils.c \
       $(SRC_DIR)/check.c \
       $(SRC_DIR)/gadget.c \
       $(SRC_DIR)/daemon.c \
       $(SRC_DIR)/config.c \
       $(SRC_DIR)/gadgets/storage.c \
       $(SRC_DIR)/gadgets/hid.c \
       $(SRC_DIR)/gadgets/tether.c \
       $(SRC_DIR)/net/netlink.c \
       $(SRC_DIR)/net/iptables.c \
       $(SRC_DIR)/net/dhcp.c \
       $(SRC_DIR)/net/ra.c \
       $(SRC_DIR)/net/uplink.c \
       $(SRC_DIR)/net/netns.c

CFLAGS  = -Wall -Wextra -Wpedantic -Werror -O2 -flto=auto -std=gnu99 -I$(SRC_DIR)/include -no-pie -pthread
CFLAGS += -Wformat=2 -Wformat-security -Wformat-overflow=2 -Wformat-truncation=2
CFLAGS += -Wnull-dereference -Wcast-qual -Wlogical-op -Wshadow -Wdouble-promotion -Wundef
CFLAGS += -Wduplicated-cond -Wduplicated-branches -Wimplicit-fallthrough=3
CFLAGS += -fstack-protector-strong
LDFLAGS = -static -no-pie -flto=auto -pthread

ARCH := $(shell $(CC) -dumpmachine 2>/dev/null | cut -d'-' -f1 | \
        sed 's/i.86/x86/; s/armv7l/armhf/; s/^arm.*/armhf/' || echo "x86_64")

OBJ_DIR = $(OUT_DIR)/.obj/$(ARCH)
OBJS    = $(SRCS:$(SRC_DIR)/%.c=$(OBJ_DIR)/%.o)

HOME_VAR := $(shell echo $$HOME)
find-cc = $(shell \
	if [ -n "$(MUSL_CROSS)" ] && [ -f "$(MUSL_CROSS)/$(1)-gcc" ]; then \
		echo "$(MUSL_CROSS)/$(1)-gcc"; \
	elif [ -f "$(HOME_VAR)/toolchains/$(1)-cross/bin/$(1)-gcc" ]; then \
		echo "$(HOME_VAR)/toolchains/$(1)-cross/bin/$(1)-gcc"; \
	elif command -v $(1)-gcc >/dev/null 2>&1; then \
		echo "$(1)-gcc"; \
	elif [ -f "/opt/cross/bin/$(1)-gcc" ]; then \
		echo "/opt/cross/bin/$(1)-gcc"; \
	fi)

.PHONY: all help clean native x86_64 aarch64 armhf x86 format module bump

all: help

help:
	@echo "USB Arsenal v$(VERSION) build system"
	@echo ""
	@echo "Build (static, musl):"
	@echo "  make native    - Build for this machine's architecture"
	@echo "  make aarch64   - Build for 64-bit ARM (most phones, Raspberry Pi 3/4/5)"
	@echo "  make armhf     - Build for 32-bit ARM (hard-float)"
	@echo "  make x86_64    - Build for 64-bit x86"
	@echo "  make x86       - Build for 32-bit x86"
	@echo ""
	@echo "Options:"
	@echo "  V=1            - Show full compiler commands"
	@echo "  MUSL_CROSS=dir - Directory holding <triplet>-gcc"
	@echo "  NO_STRIP=1     - Keep symbols"
	@echo ""
	@echo "Other:"
	@echo "  make module    - Build every arch into a KernelSU/Magisk module zip"
	@echo "  make bump NEW=x.y.z - Set the version (usbarsenal.h and module.prop)"
	@echo "  make format    - Run clang-format over src/"
	@echo "  make clean     - Remove build output (run after editing a header)"

$(OBJ_DIR)/%.o: $(SRC_DIR)/%.c
	$(msg_cc)
	$(Q)mkdir -p $(dir $@)
	$(Q)$(CC) $(CFLAGS) -c $< -o $@

$(BINARY_NAME): $(OBJS)
	$(msg_ld)
	$(Q)mkdir -p $(OUT_DIR)
	$(Q)$(CC) $(OBJS) -o $(OUT_DIR)/$(BINARY_NAME) $(LDFLAGS)
	@if [ -z "$(NO_STRIP)" ]; then \
		STRIP_CMD=$$(echo $(CC) | sed 's/-gcc$$/-strip/; s/gcc$$/strip/'); \
		if command -v $$STRIP_CMD >/dev/null 2>&1 || [ -f "$$STRIP_CMD" ]; then \
			$$STRIP_CMD -s $(OUT_DIR)/$(BINARY_NAME) 2>/dev/null || true; \
		fi; \
	fi
	@echo "[+] Built: $(OUT_DIR)/$(BINARY_NAME)"

NATIVE_TRIPLET := $(shell $(CC) -dumpmachine 2>/dev/null)
NATIVE_ARCH    := $(shell echo "$(NATIVE_TRIPLET)" | cut -d'-' -f1 | sed 's/i.86/i686/')
ifneq ($(findstring musl,$(NATIVE_TRIPLET)),)
  NATIVE_CC := $(CC)
else
  NATIVE_CC := $(call find-cc,$(NATIVE_ARCH)-linux-musl)
endif

native:
	@if [ -n "$(NATIVE_CC)" ]; then $(MAKE) -j$(NPROC) $(BINARY_NAME) CC="$(NATIVE_CC)"; \
	else echo "Error: $(NATIVE_ARCH)-linux-musl-gcc not found (set MUSL_CROSS)"; exit 1; fi

x86_64:
	@CROSS_CC="$(call find-cc,x86_64-linux-musl)"; \
	if [ -n "$$CROSS_CC" ]; then $(MAKE) -j$(NPROC) $(BINARY_NAME) CC=$$CROSS_CC; \
	else echo "Error: x86_64-linux-musl-gcc not found (set MUSL_CROSS)"; exit 1; fi

aarch64:
	@CROSS_CC="$(call find-cc,aarch64-linux-musl)"; \
	if [ -n "$$CROSS_CC" ]; then $(MAKE) -j$(NPROC) $(BINARY_NAME) CC=$$CROSS_CC; \
	else echo "Error: aarch64-linux-musl-gcc not found (set MUSL_CROSS)"; exit 1; fi

x86:
	@CROSS_CC="$(call find-cc,i686-linux-musl)"; \
	if [ -n "$$CROSS_CC" ]; then $(MAKE) -j$(NPROC) $(BINARY_NAME) CC=$$CROSS_CC; \
	else echo "Error: i686-linux-musl-gcc not found (set MUSL_CROSS)"; exit 1; fi

armhf:
	@CROSS_CC="$(call find-cc,arm-linux-musleabihf)"; \
	if [ -n "$$CROSS_CC" ]; then $(MAKE) -j$(NPROC) $(BINARY_NAME) CC=$$CROSS_CC; \
	else echo "Error: arm-linux-musleabihf-gcc not found (set MUSL_CROSS)"; exit 1; fi

# One zip for every device: customize.sh keeps the binary for the device ABI
module:
	@rm -rf $(OUT_DIR)/module
	@mkdir -p $(OUT_DIR)/module/bin
	@# objects live per arch under .obj/, so the four builds do not collide
	@for a in aarch64 armhf x86_64 x86; do \
		$(MAKE) --no-print-directory $$a || exit 1; \
		mv $(OUT_DIR)/$(BINARY_NAME) $(OUT_DIR)/module/bin/$(BINARY_NAME)-$$a || exit 1; \
	done
	@cp module/module.prop module/customize.sh module/service.sh module/uninstall.sh module/sepolicy.rule $(OUT_DIR)/module/
	@sed -i 's/^version=.*/version=v$(VERSION)/; s/^versionCode=.*/versionCode=$(VERSION_CODE)/' $(OUT_DIR)/module/module.prop
	@cd $(OUT_DIR)/module && rm -f ../$(BINARY_NAME)-v$(VERSION).zip && zip -qr9 ../$(BINARY_NAME)-v$(VERSION).zip .
	@echo "[+] Built: $(OUT_DIR)/$(BINARY_NAME)-v$(VERSION).zip"

bump:
	@echo "$(NEW)" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$$' || { echo "Usage: make bump NEW=x.y.z"; exit 1; }
	@sed -i 's/define UA_VERSION ".*"/define UA_VERSION "$(NEW)"/' $(SRC_DIR)/include/usbarsenal.h
	@code=$$(echo $(NEW) | awk -F. '{print $$1*1000 + $$2*100 + $$3*10}'); \
		sed -i "s/^version=.*/version=v$(NEW)/; s/^versionCode=.*/versionCode=$$code/" module/module.prop
	@echo "[+] Version $(VERSION) -> $(NEW). Commit it as: usb-arsenal: bump v$(NEW)"

format:
	@command -v clang-format >/dev/null 2>&1 || { echo "Error: clang-format not found"; exit 1; }
	@find $(SRC_DIR) -name '*.c' -o -name '*.h' | xargs clang-format -i
	@echo "[+] Formatted all source files"

clean:
	@rm -rf $(OUT_DIR)
	@echo "[+] Cleaned build artifacts"
