# Containers and the daemon

The kernel puts the tether interface in Android's own network namespace, and an image path
inside a container means nothing to Android. So one root daemon on the Android side owns
the USB port, configfs, the tether interface and NAT. Every other copy of `usb-arsenal`,
whether in an Android shell, a Droidspaces container or a chroot, sends its command line
to the daemon and prints what comes back. The commands look and behave the same
everywhere.

## Running the daemon

The KernelSU module starts it at boot. By hand:

```sh
usb-arsenal daemon               # goes to the background; refuses a second copy
usb-arsenal daemon --foreground  # stays attached, logs to the terminal
usb-arsenal daemon stop
usb-arsenal version              # "mode: daemon (...)", "direct", or "no access"
```

Stopping the daemon does not stop the gadget; `usb-arsenal stop` does that.

## Reaching it from a Droidspaces container

1. Bind-mount the daemon's `Run/` directory into the container, then restart the
   container:
   ```
   -B /data/local/USB-Arsenal/Run:/var/lib/USB-Arsenal/Run
   ```
   or `bind_mounts=` in `container.config`. Mount the directory, not the socket file: a
   bind-mounted socket file keeps pointing at the old socket after the daemon restarts.
2. Copy the binary in (`/data/local/USB-Arsenal/bin/usb-arsenal` on the Android side, a
   link to the module's binary; `cp` copies the file it points at) and run it as usual. `usb-arsenal version` should say `mode: daemon`.
3. For users other than root, inside the container:
   ```sh
   groupadd -g 3460 usb-arsenal
   usermod -aG usb-arsenal <user>
   ```

Then storage, HID and tethering all work from the container. Tethering in particular
works because the network side runs on the Android side.

**Without the daemon** (no `Run/` mounted), a root container or chroot that shares
Android's network namespace still works: the binary drives the gadget itself, and
`usb-arsenal version` says `mode: direct`. It then keeps its own hand-back record in the
container's `/var/lib/USB-Arsenal`, and says so when it takes the port, so do not run
USB Arsenal from Android at the same time. Stop the gadget before stopping the container.

## Who may use it

Root, and members of the `usb-arsenal` group, **gid 3460**. On Android the number is what
counts, since Android has no group database. `daemon --gid N` picks another number, and
on a Linux host a real `usb-arsenal` group is used if one exists. Everyone else gets
"Permission denied". Membership means control of the USB port, so grant it like `sudo`.

On kernels older than 4.13, supplementary groups cannot be seen across the container
boundary. A non-root user there needs the group as their primary group.

## Images and paths

`storage start /root/disk.img` inside a container works as typed. The client opens the
file itself, with your own permissions, and passes the open file to the daemon; the kernel
then reads the file through it, wherever it lives. `identity set` does the same with
profiles. When the daemon cannot see the file directly, it keeps a copy;
see [identity.md](identity.md).

## The workspace

Everything USB Arsenal keeps lives in one directory:

| | Android | Linux, and inside any container |
|---|---|---|
| Workspace | `/data/local/USB-Arsenal` | `/var/lib/USB-Arsenal` |

| Path | Holds |
|---|---|
| `usb-arsenald.pid` | the daemon's pid |
| `Run/usb-arsenal.sock` | the daemon's socket (`0660 root:3460`, in a `0750` directory) |
| `Logs/` | `usb-arsenald.log`, `tether.log` |
| `Pids/`, `Net/` | the tether process's pid and status |
| `Gadget/` | the hand-back record, the active profile, HID permissions |
| `bin/` | a copy of the binary |
