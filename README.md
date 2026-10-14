# phone-agent

A minimal Android agent that lets **me** (an operator with SSH access to a relay)
read a phone's location and drive its UI. It is the fourth client of an existing
self-hosted remote-control channel — the phone dials **out** to the relay, so
nothing needs to be exposed on the phone's network.

- **No root.** No Shizuku. No ADB. No Google Play Services.
- **Zero third-party dependencies.** JDK + Android framework only.
- **~2.4 MB APK**, built entirely in GitHub Actions (no local Android SDK needed).
- **Android 13+** (`minSdk 33`), `targetSdk 34`.

## What it can do

| Area | Commands |
|---|---|
| Screen | `shot` (screenshot, no MediaProjection prompt), `tree` (node tree), `status` |
| Touch | `tap`, `long`, `swipe` |
| Keys | `key back\|home\|recents\|notifications\|quicksettings` |
| Text | `text <string>` |
| Apps | `apps`, `app <package>` |
| Location | `loc`, `locloop <seconds>`, `locstop` |
| Lifecycle | `ping`, `stop` |

Everything is driven by one-line commands delivered over the relay's long poll,
and every command returns `exit` + `stdout` + `stderr`.

### What it deliberately cannot do

There is **no arbitrary shell execution**. An app in the `untrusted_app` SELinux
domain cannot run shell commands without root, and unlike many "AI phone control"
projects this one does not pretend otherwise. Anything needing shell (installing
APKs, `pm grant`, reading `/proc`) must be done once over a USB ADB session.

## How it talks to the relay

The phone POSTs to three endpoints with an `X-RC-Secret` header:

```
POST /agent/hello    register + heartbeat (also the liveness signal)
POST /agent/poll     long poll (up to 25 s) for the next queued command
POST /agent/result   report exit code, stdout, stderr
```

Delivery is **at-most-once** by design: a command is removed from the queue when
handed out. A missed command can be re-sent; a double-executed destructive action
cannot be undone.

See [SPEC.md](SPEC.md) for the frozen contract, including the exact command
grammar, the node-tree format, and the screenshot size ladder.

## Install

1. Download `app-debug.apk` from the latest green run's **Artifacts**
   (artifacts require a GitHub login).
2. Sideload it and open **Phone Agent**.
3. Fill in the relay base URL and the shared secret, then press **保存**.
4. Press **请求权限** and grant notifications + location (including
   *Allow all the time* for background reporting).
5. Press **打开无障碍设置** and enable **Phone Agent**.
6. Press **打开电池优化设置** and set the app to *Not optimized*.
7. Press **启动**.

> On Android 13+, a sideloaded app may be blocked from enabling accessibility
> under *Restricted settings*. Clearing that once over USB ADB with
> `adb shell appops set net.de5.phoneagent ACCESS_RESTRICTED_SETTINGS allow`
> avoids a lot of manual tapping.

On Flyme (Meizu) also allow **自启动**, set the battery profile to **无限制**, and
enable **允许后台活动** — otherwise the system will kill the agent.

## Privacy notes

- The phone reports **coordinates only**; reverse geocoding ("where is this?")
  happens on the operator's server, so no map API key ships in the APK.
- The APK contains no telemetry and no third-party SDK.
- Anyone holding the relay secret has full control of the device. Treat it as a
  password.

## Licensing

The APK bundles no third-party code, so there is a single licence to honour: MIT
(see [LICENSE](LICENSE)). The architecture and several accessibility techniques
were learned from
[`danielealbano/android-remote-control-mcp`](https://github.com/danielealbano/android-remote-control-mcp)
(MIT), which is a far more feature-complete project — worth a look if you want
MCP instead of this minimal channel.
