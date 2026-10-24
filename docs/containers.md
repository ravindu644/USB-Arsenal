# Containers

USB Arsenal has no daemon. Every copy of `usb-arsenal` drives the kernel itself, as root,
from wherever it runs. A privileged container, such as Droidspaces with configfs and sysfs
visible, runs the binary directly. On the a166p, inside an OpenWrt container, `hid start`
served `/dev/hidg1` and `/dev/hidg2`. `stop` from the container then handed the port back to
Android's own tethering, with `android0` intact.

## Running it in a container

1. Copy the binary in. On the Android side it is `/data/local/USB-Arsenal/bin/usb-arsenal`.
2. Bind-mount Android's workspace over the container's, then restart the container:
   ```
   -B /data/local/USB-Arsenal:/var/lib/USB-Arsenal
   ```
   You can also use `bind_mounts=` in `container.config`. Both sides then share one hand-back
   record, one lock and one setup: a command from the app and one from the container queue
   behind each other, and whichever side runs `stop` gives the port back.
3. Run it as root, as usual.

Without the bind mount, the container keeps its own hand-back record in its own
`/var/lib/USB-Arsenal`, and says so when it takes the port. Do not run USB Arsenal from
Android (the app included) at the same time then. Stop the gadget before stopping the
container.

## Tethering

The kernel creates the tether interface (`usb0`) in Android's own network namespace, not in
the container's. A container in its own namespace cannot see it, so `tether start` from
there fails. Run the container in Droidspaces' **host networking mode** to tether from it.
The container then shares Android's network stack, at the cost of that isolation.

The gateway mode (`tether start --gateway CONTAINER`, the app's Gateway switch) is the other
way round: it runs on the Android side and hands `usb0` to a container. It needs nothing in
the container.

## Paths

Images and profiles are opened by name, in the mount namespace of the copy that runs the
command. A path private to the container (`/root/disk.img`) works from the container, but saved
into the setup it fails a later start from the app, which names the file. The same goes for
`identity set`, whose link names the file. Keep images and profiles on a path both sides see,
such as `/sdcard/USB-Arsenal` bind-mounted in.

## The workspace

Everything USB Arsenal keeps lives in one directory:

| | Android | Linux, and inside any container |
|---|---|---|
| Workspace | `/data/local/USB-Arsenal` | `/var/lib/USB-Arsenal` |

| Path | Holds |
|---|---|
| `Logs/` | `usb-arsenal.log`, every run and the tether process, one timestamped line each |
| `Pids/`, `Net/` | the tether process's pid and status |
| `Gadget/` | the hand-back record, the lock, the setup, the active profile, HID permissions |
| `bin/` | the binary |
