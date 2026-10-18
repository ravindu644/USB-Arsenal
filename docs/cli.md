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
| `tether` | `start`, `stop` | `--rndis`, `-u/--upstream IFACE`, `-a/--address CIDR`, `-d/--dns IP[,IP]`, `--no-net`, `-f/--foreground`, `-G/--gateway NAME`, `--gateway-pid PID`, `--gateway-netns PATH`, `--gateway-iface IFACE` |
| `stop` | | |
| `identity` | `generate FILE`, `set FILE`, `show`, `unset` | `--force` (generate) |
| `daemon` | `stop` | `-f/--foreground`, `-g/--gid GID` |
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
`tether start` forks a process that holds the network side (DHCP and NAT, or the gateway
cable), and writes `Pids/tether.pid`. `tether stop` and `stop` send it SIGTERM.

Defaults for every command, and the IDs and strings the host sees, come from the active
profile; see [identity.md](identity.md). An explicit option always wins.

When `usb-arsenal daemon` is running, every command except `daemon`, `help` and
`version` is relayed to it and runs there; see [daemon.md](daemon.md). `UA_NO_PROXY=1`
forces direct mode.

Workspace: `/data/local/USB-Arsenal` on Android, `/var/lib/USB-Arsenal` on Linux and
inside containers, laid out like Droidspaces' (`Logs/`, `Pids/`, `Net/`, `Gadget/`,
`Run/`); see [daemon.md](daemon.md#workspace).
