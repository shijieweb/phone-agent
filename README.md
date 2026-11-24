# phone-agent

A minimal Android agent that lets **me** (an operator with SSH access to a relay)
read a phone's location and drive its UI. It is the fourth client of an existing
self-hosted remote-control channel — the phone dials **out** to the relay, so
nothing needs to be exposed on the phone's network.

- **No root.** No Shizuku. No ADB. No Google Play Services.
- **Zero third-party dependencies.** JDK + Android framework only.
- **~2.5 MB APK**, built entirely in GitHub Actions (no local Android SDK needed).
- **Android 12+** (`minSdk 31`), `targetSdk 34`.

## What it can do

| Area | Commands |
|---|---|
| Screen | `shot [b64] [maxPx] [quality]` (screenshot, no MediaProjection prompt — uploaded as a blob and answered with one line of JSON; `b64` forces the old base64-on-stdout output), `tree` (node tree), `status` |
| Touch | `tap`, `long`, `swipe` |
| Keys | `key back\|home\|recents\|notifications\|quicksettings` |
| Text | `text <string>` |
| Apps | `apps`, `app <package>` |
| Location | `loc`, `locloop <seconds>`, `locstop` |
| Elements | `click [apply] SEL`, `longclick [apply] SEL`, `scroll`, `ime enter`, `confirm [apply] [t=N] <text>` — **dry run by default**: they report the node they *would* press; add `apply` to really press it |
| Apps | `install <url>` (opens the system installer), `uninstall <pkg>` |
| Privileged | `settings`, `logs`, `a11y heal`, `admin status\|release`, `lock` (works), `reboot` (always exit 6 — see below) |
| Wireless ADB | `adbinfo` (IP + mDNS port + `adb connect` string), `adbheal [t=N]` (bounce wireless debugging off/on, then rediscover — this drops a live adb session). The port is also rediscovered on its own ~20 s after boot, and when the cache is 30 min stale, so `adb connect` keeps working without a command |
| Files | `dl <url> [name]` — download into `/sdcard/Movies` and media-scan it so VLC lists it (cap 2 GB, `t=` timeout) |
| Lifecycle | `ping`, `stop` |

`stop` is authoritative: pressing it in the app also clears "start on boot", so
the agent cannot come back by itself after a reboot or an update. Re-arm it with
the `启动` button or the `开机自动启动` checkbox.

Everything is driven by one-line commands delivered over the relay's long poll,
and every command returns `exit` + `stdout` + `stderr`.

Screenshots do not fit through that text channel (the relay truncates `stdout`
at 200 000 chars), so `shot` **uploads the JPEG as a blob** — `PUT
/agent/blob/<name>` — and answers with one line of JSON naming it. The
controller then fetches `/ctl/blob/<name>`. `shot b64` restores the old
base64-on-stdout output for a human at a terminal.

### What it deliberately cannot do

There is **no arbitrary shell execution**. An app in the `untrusted_app` SELinux
domain cannot run shell commands without root, and unlike many "AI phone control"
projects this one does not pretend otherwise. Anything needing shell (`pm grant`,
reading `/proc`) must be done once over a USB ADB session.

**No silent install or uninstall either.** `INSTALL_PACKAGES` / `DELETE_PACKAGES`
are `signature|privileged` without the `development` flag, so a sideloaded app
cannot hold them and *Device Owner does not change that*. `install` therefore
opens the system installer and `confirm apply <text>` presses its button — two
commands, no lie about authority. Run `confirm <text>` first without `apply` to
see which button it would press before letting it press anything.

What `adb shell pm grant` *can* hand over is `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
`DUMP` and `PACKAGE_USAGE_STATS`; `admin status` reports each one's real state and
every command that needs one fails with `exit=6` and the exact grant line.

**No reboot, either.** `reboot` always answers exit 6, and no setup changes that:
`PowerManager.reboot` needs `REBOOT`, which is `signature|privileged` (the same
wall as `INSTALL_PACKAGES`, and `pm grant` cannot touch a signature
permission), while the Device Owner door `DevicePolicyManager.reboot` needs
provisioning on a factory-reset device, which this app never asks for. `lock`
does work — `DevicePolicyManager.lockNow` only needs an active device admin.

## How it talks to the relay

The phone POSTs to three endpoints with an `X-RC-Secret` header:

```
POST /agent/hello    register + heartbeat (also the liveness signal)
POST /agent/poll     long poll (up to 25 s) for the next queued command
POST /agent/result   report exit code, stdout, stderr
```

The default relay is `https://dsh.2-c.cc:19092` — a **direct** route on the
operator's own paid domain, with no Cloudflare in front. The relay itself
listens on loopback only; `nginx` on the VPS terminates TLS for that name and
proxies it.

> **Why the default has changed three times.** Every address this app shipped
> before v0.7.0 lived under `de5.net`, a free domain handed out to anyone who
> asked — and it is exactly that shared-ness that gets a name onto a carrier's
> TLS-SNI block list. A connection to one of those names is killed with a forged
> TCP RST immediately after the ClientHello — 13–29 ms, far inside one RTT, so
> the reset is injected near the client, not by our server (which logs nothing
> and never sends it: the ClientHello arrives, our ServerHello and certificate
> go out, and the RST comes *in*).
>
> The first fix — fronting the relay with Cloudflare on `mcp.owen1.de5.net` — was
> not enough, because the blocking turned out not to be about the port, the TLS
> version, the ALPN set or the client's TLS fingerprint. The probe that settled
> it holds everything constant except the SNI, and dials both an unrelated host
> and our own: an unrelated name completes the handshake every time (reaching
> *our* `nginx` and returning our dummy certificate), while our names — and even
> a bare no-SNI handshake — are reset. So the reset is aimed at **specific
> names**, and the cure is to stop using one of them rather than to disguise the
> connection. `2-c.cc` is registered to the operator alone, and its name was
> never on that list — the same IP and non-standard port handshake fine under it.
>
> `Prefs.RETIRED_RELAY_BASES` records all three addresses this app used to ship
> as its default (`dsh.owen1.de5.net:19092`, `mcp.owen1.de5.net`, and the
> Cloudflare detour `relay.2-c.cc`); an install still holding any of them
> migrates to the current default automatically (see `resolveRelayBase`). A
> hand-typed address is never rewritten.

Delivery is **at-most-once** by design: a command is removed from the queue when
handed out. A missed command can be re-sent; a double-executed destructive action
cannot be undone.

See [SPEC.md](SPEC.md) for the frozen contract, including the exact command
grammar, the node-tree format, and the screenshot blob/base64 paths.

## Install

1. Download `app-debug.apk` from the latest green run's **Artifacts**
   (artifacts require a GitHub login), or from the operator's own mirror.
2. Sideload it and open **Phone Agent**.
3. The relay base URL is pre-filled with the operator's default; paste the shared
   secret and press **保存**.
4. Press **请求权限** and grant notifications + location (including
   *Allow all the time* for background reporting).
5. Press **打开无障碍设置** and enable **Phone Agent**.
6. Press **打开电池优化设置** and set the app to *Not optimized*.
7. Press **启动**.

> **Upgrades install in place.** Builds are signed with a stable key held in
> `keystore.properties` + `keystore/` locally, and injected in CI from the
> `SIGNING_KEY_BASE64` secret — so a newer APK replaces an older one without
> uninstalling, keeping the relay config. If you are upgrading from a build made
> *before* that key existed, one uninstall is unavoidable (the old and new
> signatures differ, no matter which key is used going forward).

> On Android 13+, a sideloaded app may be blocked from enabling accessibility
> under *Restricted settings*. Clearing that once over USB ADB with
> `adb shell appops set net.de5.phoneagent ACCESS_RESTRICTED_SETTINGS allow`
> avoids a lot of manual tapping.

On Flyme (Meizu) also allow **自启动**, set the battery profile to **无限制**, and
enable **允许后台活动** — otherwise the system will kill the agent.

## When it does not connect

The phone cannot be reached by ADB, so the app diagnoses itself. Press
**诊断并复制（推荐）** and paste the clipboard into a message; the report is
designed so one paste is enough to locate the fault.

The report is a layered probe, because `SocketException: Connection reset` alone
cannot tell apart four very different causes:

| Probe | What it separates |
|---|---|
| `environment` | a VPN or proxy rewriting traffic (also prints `http.proxyHost`) |
| `dns` | a rewritten/hijacked answer — flags a missing expected address |
| `tcp` | dials the resolved address **and** a hard-coded one: distinguishes "DNS is lying" from "server is down" |
| `tls` | reads the peer certificate **with SNI and without** — a real cert means nginx matched us; a self-signed one proves the ClientHello landed on the default server (`return 444`) |
| `https` | `HttpsURLConnection` all the way to `/agent/hello` (403 = reached the relay, wrong secret) |
| `rawHttp` | hand-written request on a raw TLS socket, bypassing `HttpURLConnection` entirely |

The service also copies a report to the clipboard **once per run** on its first
failure — not per outage, so a flat link cannot keep overwriting whatever has not
been pasted yet. `复制日志` gets the log alone with no network traffic; `清空日志`
clears the in-memory ring buffer (400 lines; nothing is ever written to disk).

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
