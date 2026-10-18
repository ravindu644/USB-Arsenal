# Command line

```
usb-arsenal [--udc NAME] [-v] <command> [<action>] [options] [args]
```

The shape is git's (`git [global options] <command> [<args>]`, with actions nested as in
`git remote add`), the same one docker (`docker container ls`) and iproute2 (`ip link
set`) use. Help follows https://clig.dev: every command explains itself, with examples.

## Rules

- Global options (`--udc`, `-v`, `-h`, `-V`) come before the command. Parsing stops at
  the command name (`getopt_long` with `+`).
- A command's options may come before or after its action:
  `storage --mode cdrom start x.iso` and `storage start x.iso --mode cdrom` are the
  same. `getopt_long` permutes arguments on both glibc and musl.
- `--` ends option parsing.
- `usb-arsenal <command> --help`, `usb-arsenal help <command>` and a bare
  `usb-arsenal <command>` all print the same page: usage, actions, options, examples.
  Each subsystem file owns its own help text.
- A bare `usb-arsenal` prints the command list.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Done |
| 1 | Runtime failure (missing kernel feature, configfs refused a write, ...) |
| 2 | Usage error: unknown command, action or option, or a bad value. The message ends with "See 'usb-arsenal <command> --help'." |

## Commands

| Command | Actions | Options |
|---|---|---|
| `check` | | |
| `status` | | |
| `storage` | `start IMAGE`, `eject`, `stop` | `-m/--mode auto\|cdrom\|disk\|rw`, `-l/--lun N` |
| `hid` | `start`, `stop` | `-p/--perm MODE`, `-g/--group GROUP` |
| `tether` | `start`, `stop` | `--rndis`, `-u/--upstream IFACE`, `-a/--address CIDR`, `-d/--dns IP[,IP]`, `--no-net`, `-f/--foreground` |
| `stop` | | |
| `help [command]`, `version` | | |

## Composition

There is one gadget, and each command adds or removes its function in it. If the gadget
is bound, the command unbinds it, changes the function, and rebinds. Removing the last
function tears the gadget down and gives the controller back. The source of truth is
configfs itself; the only state file is the handoff record (see
[research/02-android-usb-handoff.md](research/02-android-usb-handoff.md)).

`storage start` on a LUN that is already live only swaps the image, with no rebind.

## Processes

`storage` and `hid` are one-shot: the gadget lives in the kernel, not in our process.
`tether start` forks a daemon that holds the DHCP server and the NAT rules, and writes
`tether.pid` in the state directory. `tether stop` and `stop` send it SIGTERM.

State directory: `/data/local/tmp/usb-arsenal` on Android, `/run/usb-arsenal` elsewhere
(`/tmp/usb-arsenal` if `/run` is not writable). The tether daemon logs to `tether.log`
there.
