# phone-agent

A minimal Android agent that lets **me** (an operator with SSH access to a relay)
read a phone's location and drive its UI. It is the fourth client of an existing
self-hosted remote-control channel — the phone dials **out** to the relay, so
nothing needs to be exposed on the phone's network.

- **No root.** No Shizuku. No ADB. No Google Play Services.
- **Zero third-party dependencies.** JDK + Android framework only.
- **~2.5 MB APK**, built entirely in GitHub Actions (no local Android SDK needed).
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

The default relay is `https://relay.2-c.cc` — a Cloudflare-fronted name on the
operator's own domain. The relay itself listens on loopback only; the tunnel is
what makes it reachable.

> **Why not dial the server directly?** On the operator's mobile carrier, a TLS
> connection to the direct address (`dsh.owen1.de5.net:19092`) is killed with a
> forged TCP RST immediately after the ClientHello. Packet capture showed the
> reset carrying a different TTL and IP ID than the phone's own packets, with no
> server-side log entry at all — i.e. injected in transit.
>
> The first fix — fronting the relay with Cloudflare on `mcp.owen1.de5.net` — was
> not enough, because the blocking turned out not to be about the port, the TLS
> version, the ALPN set or the client's TLS fingerprint. The probe that settled
> it holds everything constant except the SNI, and dials both an unrelated host
> and our own: an unrelated name completes the handshake every time (reaching
> *our* `nginx` and returning our dummy certificate), while our names — and even
> a bare no-SNI handshake — are reset. So the reset is aimed at **specific
> names**, and the cure is to stop using one of them rather than to disguise the
> connection.
>
> `Prefs.RETIRED_RELAY_BASES` records both addresses this app used to ship as its
> default; an install still holding either migrates to the current default
> automatically (see `resolveRelayBase`). A hand-typed address is never
> rewritten.

Delivery is **at-most-once** by design: a command is removed from the queue when
handed out. A missed command can be re-sent; a double-executed destructive action
cannot be undone.

See [SPEC.md](SPEC.md) for the frozen contract, including the exact command
grammar, the node-tree format, and the screenshot size ladder.

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
