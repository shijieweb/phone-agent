# phone-agent

A minimal Android agent that lets **me** (an operator with SSH access to a relay)
read a phone's location and drive its UI. It is the fourth client of an existing
self-hosted remote-control channel — the phone dials **out** to the relay, so
nothing needs to be exposed on the phone's network.

- **No root.** No Shizuku. No Google Play Services. No PC in the loop — since
  v0.5.0 the app pairs with **its own** `adbd` over `127.0.0.1` and gets a real
  `uid=2000` shell, with no cable and no companion process.
- **Zero third-party dependencies.** JDK + Android framework only. The one thing
  that is not framework code is vendored source compiled into the APK — see
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
- **~2.5 MB APK**, built entirely in GitHub Actions (no local Android SDK needed).
- **Android 12+** (`minSdk 31`), `targetSdk 34`.

## What it can do

The app has two screens: the config page and, hidden behind five presses on the
version line at the bottom, an operator's debug page that runs one command line
through the live agent and shows the answer — same `Commands.parse` path as the
relay, no round trip. See `SPEC.md` §6.1.

Since v0.5.8-pad12 the config page is written for the phone's owner, not for me:
one big 运行中 / 已停止 word, one 启动 button, two switches, and a single
一键设置权限 button that does the next missing thing instead of asking which of
four buttons to press. URL / secret moved into a collapsed 高级设置 section.

Logs are capped in all three places they accumulate — the in-memory ring (400
lines), the watchdog log on `/data/local/tmp` (64 KB, trimmed by the script
itself before each beat) and cached APKs (24 h). `logs size` prints the three
numbers, `logs trim` shows what it would delete, `logs trim apply` does it.
See `SPEC.md` §3.18.

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
| Privileged | `settings`, `logs`, `a11y status\|heal\|rescue`, `admin status\|release`, `lock` (works), `reboot` (always exit 6 — see below) |
| Wireless ADB | `adbinfo` (IP + mDNS port + `adb connect` string), `adbheal [t=N]` (bounce wireless debugging off/on, then rediscover — this drops a live adb session). The port is also rediscovered on its own ~20 s after boot, and when the cache is 30 min stale, so `adb connect` keeps working without a command |
| Files | `dl <url> [name]` — download into `/sdcard/Movies` and media-scan it so VLC lists it (cap 2 GB, `t=` timeout) |
| Storage | `files ls [path] [n]`, `files cat`, `files info`, `files mkdir`, `files rm [apply]`, `files find path keyword [n]`, `files df`, `files pull path` — **`/sdcard` only**, never outside it; `rm` is a dry run until `apply`; `pull` is the only verb that moves bytes out (16 MiB blob cap) |
| Notifications | `notif [n] [q=text]` — the active notifications, once notification access is granted |
| Voice | `say [-l zh\|en] [rate=N] [pitch=N] <text>` — speak one sentence through the system TTS engine (Chinese by default) |
| Screen | `screen stayon [on\|off]` (sticky across reboots), `screen timeout <ms>`, `screen on`, `screen off`; the same stay-on switch is a checkbox in the app |
| SMS | `sms [list [n]]`, `sms send [apply] <number> <text>` — sending is a dry run until `apply` |
| Calls | `call [apply] <number>` (dry run until `apply`), `call answer`, `call hangup`, `call state`, `call speaker [on\|off]` (no argument = report) |
| Animation | `anim [get]`, `anim set on\|off\|<scale>`, `anim save`, `anim restore` — turn the three animation scales off to make the UI snap |
| Self-ADB | `shinfo`, `sh [t=N] [apply] <cmd>` (**dry run** until `apply`), `shpair <6 digits>`, `shconnect`, `shkey [show]` / `shkey regen [apply]` / `shkey import <url>` — the app talks to **its own** adbd over `127.0.0.1` and runs commands as uid 2000; no PC, no root, no third-party binary (see below) |
| Self-start | opening the app starts the agent again when 开机自动启动 is ticked; a `uid 2000` guard script in `/data/local/tmp` revives it after `am force-stop`, and `watchdog [status\|arm\|disarm]` drives it by hand |
| Lifecycle | `ping`, `stop` |

`stop` is authoritative, and it is the same rule from both doors: pressing 停止 in
the app and sending `stop` over the relay both clear "start on boot" before the
service is touched, so the guard is disarmed and the agent cannot come back by
itself after a reboot, an update, a `force-stop` or somebody opening the UI.
Re-arm it with the `启动` button or the `开机自动启动` checkbox.

Everything is driven by one-line commands delivered over the relay's long poll,
and every command returns `exit` + `stdout` + `stderr`.

Screenshots do not fit through that text channel (the relay truncates `stdout`
at 200 000 chars), so `shot` **uploads the JPEG as a blob** — `PUT
/agent/blob/<name>` — and answers with one line of JSON naming it. The
controller then fetches `/ctl/blob/<name>`. `shot b64` restores the old
base64-on-stdout output for a human at a terminal.

### After a silent install, what starts the app?

A silent `pm install -r` (see Self-ADB above) leaves nothing running, and Flyme
delivers **no** package-replaced broadcast — measured, not assumed. Two things
cover it:

1. **Opening the UI starts the service**, gated by `StartPolicy.shouldStartOnOpen`
   (`configured && autoStart && !running`). Pressing 停止 clears `auto_start`, so
   the app cannot be revived by somebody opening it on a phone you silenced.
2. **A guard script that outlives the app.** Once self-ADB is connected the agent
   writes `/data/local/tmp/phoneagent-guard.sh` and starts it with `setsid`, so it
   runs as `uid 2000` outside the app's process group: `am force-stop` does not
   reach it. Every 60 s it checks `dumpsys activity services net.de5.phoneagent`
   and runs `am start -n net.de5.phoneagent/.MainActivity` if the service is gone,
   appending a timestamp to `/data/local/tmp/phoneagent-guard.log`.

```
watchdog status     # 守护: 运行中（pid 12345）/ 最近重启: … / 停止标记: 不存在
watchdog arm        # start it by hand; refuses to start a second copy
watchdog disarm     # write the stop marker and pkill it
```

**The guard needs Wi-Fi.** It drives self-ADB, whose port is found over mDNS, and
mDNS multicast does not leave a mobile-data interface — measured on cellular
(`10.201.131.111`): `adbheal` sets `adb_wifi_enabled=1` but nothing resolves, so
`shconnect` fails and `watchdog arm` answers `守护未启用：自连 ADB 未连接`. On
cellular only trigger 1 (opening the UI) can restart the agent; it needs no ADB.

Three rules keep it honest: `arm` probes `pgrep -f phoneagent-guard.sh` first so a
5-minute re-arm never stacks copies; `arm` is skipped with one log line
(`守护未启用：自连 ADB 未连接`) when self-ADB is down, instead of logging a
refusal every five minutes; and pressing 停止 writes the marker **and** kills the
guard, so "stop" still means stop — a killed guard would otherwise be resurrected
by the next heartbeat and the service would return two minutes later.

`status` gains one field, `"guard":"running"|"off"|"unknown"`; no existing field
changed. The guard does not survive a reboot (`/data/local/tmp` is wiped — the
boot path is `BootReceiver`).

### What it deliberately cannot do

**Shell exists as of v0.5.0, but only through one door and only as uid 2000.** The
app pairs with **its own** `adbd` over `127.0.0.1` and runs commands there — the
same authority a `adb shell` over USB has, and nothing more. There is no root
(`sh apply id` reports `uid=2000(shell)`), no connecting to another machine, and
no `pm grant` of a signature permission from inside that shell either. `sh` is a
dry run until `apply`, because it is the most destructive command in the surface
and the one most likely to be replayed out of a log.

The transport is vendored source, not a dependency: the ADB protocol, SPAKE2
pairing and the RSA key encoding live in `app/src/main/java/io/github/muntashirakon/`
and compile into the APK. BouncyCastle was removed in the process — the two files
that used it now run on plain JCE plus a 30-line RFC 5869 HKDF, so the dependency
list is still empty. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Pairing is a two-step ritual, and the steps are deliberately not merged:

```
shpair 123456     # 无线调试 → 使用配对码配对设备; then it tries one connect
shconnect         # pairing only swaps keys; connecting is separate
shinfo            # key fingerprint, connected or not, last note
sh apply id       # uid=2000(shell) — the proof it really is a shell
shkey             # 公钥 + SHA256: fingerprint, to compare against 已配对的设备
shdest shell:     # debug: open a destination verbatim, show bytes/hex/text
```

`shkey regen` alone only prints a warning; `shkey regen apply` really replaces the
key and **invalidates the pairing**, so the old entry has to be removed on the
phone before pairing again.

**Commands run through a PTY, because this ROM's one-shot shell is broken (v0.5.4).**
adbd dispatches an `OPEN` by destination string, which must be `<service>:<args>`.
`shell:COMMAND` (the `kRaw` subprocess path) is the obvious choice and it does
not work on Flyme/Android 16: every destination carrying a command is closed
right after `OKAY` with `IOException: Stream closed.` — measured with the
`shdest` debug verb, which tries destinations verbatim and reports bytes and hex.
The one destination that stays alive is the empty-command `shell:`, which is
the interactive PTY (`kPty`) — the same path a human gets from `adb shell` with
no command, and POSIX-standard on every ROM.

So `sh` now drives the shell like a person does: wait for the prompt (1.5 s),
write the command plus a quoted sentinel line `echo "__RC=$?"`, then read until
that sentinel comes back instead of waiting for an EOF a PTY never sends. Parsing
strips ANSI escapes and the prompt (including Flyme's `127|Meizu22:/ $ ` prefix),
drops the echoed input lines, and accepts only a standalone `__RC=<digits>` line.

Two honest consequences, both in `shinfo` and in this file: `sh` reports
**merged output** in `stdout` (the `shell:` service does not separate stderr),
and `shinfo` prints the channel actually in use — `通道: PTY（shell:）`, or
`通道: RAW（降级 shell:COMMAND）` if opening the PTY stream itself failed. Raw is
only tried after PTY fails, never before: on this ROM raw always fails, so trying
it first would burn a timeout per command.

**Pairing is blocked on Android 16, and `shkey import` is the way around it.**
Pairing mixes the TLS exporter's key material into the PAKE password, and the
only entry point to that exporter is `Conscrypt.exportKeyingMaterial(...)` — a
non-SDK interface. With `targetSdk 34` on Android 16, hidden-API enforcement
makes the reflective lookup throw `NoSuchMethodException`, so the pairing
handshake cannot finish. Connecting is unaffected (plain TLS + RSA auth), which
is why an already-authorised key still works. If pairing fails this way the
answer says so and names the escape hatch:

```
配对失败：本机系统禁止非 SDK 接口（exportKeyingMaterial）。请改用 shkey import <url> 导入一把已被手机授权的密钥。
```

```
shkey import http://host/adbkey   # defaults the .pub URL to <url>.pub
shconnect                         # no pairing needed: the phone already trusts it
shkey show                        # 来源: 导入 / 公钥 / 指纹
```

It needs **no `apply`** (it does not tap the screen or delete user data, only
this app's own key files), but it **replaces the current key** — any pairing made
with the old one is gone. Validation runs before anything is written, so a bad
download leaves the working key untouched.

`shkey adopt` — appending our own key to `/data/misc/adb/adb_keys` — is
deliberately **not implemented**: on a `user` build that directory is
adbd-private and `uid 2000` gets `Permission denied` just listing it. The verb
exists only to explain that and point at `shkey import`.

**No silent install or uninstall either.** `INSTALL_PACKAGES` / `DELETE_PACKAGES`
are `signature|privileged` without the `development` flag, so a sideloaded app
cannot hold them and *Device Owner does not change that*. `install` therefore
opens the system installer and `confirm apply <text>` presses its button — two
commands, no lie about authority. Run `confirm <text>` first without `apply` to
see which button it would press before letting it press anything.

What `adb shell pm grant` *can* hand over is `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
`DUMP` and `PACKAGE_USAGE_STATS`; `admin status` reports each one's real state and
every command that needs one fails with `exit=6` and the exact grant line.

**Call audio is out of reach, and `call` does not pretend otherwise.** `call` can
place, answer, hang up and switch the speaker, but it cannot carry the call
itself: call audio goes through the modem and the audio HAL, and reading it back
needs `CAPTURE_AUDIO_OUTPUT`, which is signature-level. Recording the microphone
(`RECORD_AUDIO`) is a different thing and is not a call recording.

Three of the v0.4.0 surfaces have doors that `pm grant` cannot open, so
`admin status` prints the exact line for each:

- `files` — `adb shell appops set net.de5.phoneagent MANAGE_EXTERNAL_STORAGE allow`
  (or 设置 → 所有文件访问)
- `notif` — `adb shell cmd notification allow_listener
  net.de5.phoneagent/net.de5.phoneagent.NotifListenerService`
  (or 设置 → 通知使用权)
- `anim`, `screen` — `adb shell pm grant net.de5.phoneagent
  android.permission.WRITE_SECURE_SETTINGS` (same door as `settings put`)

`call answer`, `sms`, and reading the call log are ordinary runtime grants
(`ANSWER_PHONE_CALLS`, `READ_SMS`/`SEND_SMS`, `READ_CALL_LOG`) and `pm grant`
works. `SET_ANIMATION_SCALE` and `MODIFY_PHONE_STATE` are `signature|privileged`;
they are declared in the manifest for completeness, but no command depends on
them — `anim` writes `Settings.Global` through `WRITE_SECURE_SETTINGS`.

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

> **An upgrade rebuilds the accessibility binding by itself.** Installing over the
> old APK keeps the secure setting but drops the binding, which looks exactly like
> "accessibility is on" while every `tree`/`click` answers *accessibility service
> not connected*. The app repairs this from **three independent triggers**, because
> no single one is reliable:
>
> - **process start** — the guaranteed one. Every time the agent's process comes
>   up it checks the binding and re-binds if needed. The process always starts;
>   a broadcast may never arrive (Flyme never sent `MY_PACKAGE_REPLACED` at all).
> - **package replaced** — `MY_PACKAGE_REPLACED` and its sibling
>   `PACKAGE_REPLACED`, retried at 3 s / 10 s / 30 s. Works on most ROMs.
> - **heartbeat** — every 5 min, as a last resort.
>
> All of it needs `WRITE_SECURE_SETTINGS`; without it the log says the rebuild was
> skipped. `a11y status` tells you which of the three facts is wrong, and quotes
> the newest repair from the log with the trigger that produced it — `[装包]`,
> `[启动]` or `[心跳]` — or `(本次进程内无记录)` when nothing has run yet:
>
> ```
> a11y status
> 无障碍总开关: 开
> 无障碍条目: 已列出
> 服务实例: 未连接
> 最近自愈: 11-25 04:20:35 [启动] 已强制重连无障碍服务（保留原有 0 条）
> 结论: 条目在但服务未绑定，执行 a11y rescue 强制重绑
> ```
>
> `a11y heal` inspects and then repairs (append a missing entry, or rescue a
> listed-but-unbound one); `a11y rescue` always does the full re-bind —
> switch off, **delete** the list, wait 2 s, write it back, switch on. Deleting
> matters: writing an empty string is refused by the system (`Bad arguments`), so
> the v0.4.2 reconnect did nothing.

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

The project's own code is MIT (see [LICENSE](LICENSE)). As of v0.5.0 the APK also
contains **vendored source** — the ADB protocol client (Apache-2.0), the SPAKE2
pairing implementation (LGPL-3.0) and the Ed25519/X25519 curve code (CC0-1.0) —
copied into `app/src/main/java/` and compiled in, not pulled as a dependency.
Every vendored file keeps its original SPDX header, and the full attribution with
the exact upstream commits is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
Nothing is fetched at build time: no Maven artifact, no JitPack, no git
submodule. That is deliberate — a dependency that updates itself is a dependency
nobody reviewed.

The architecture and several accessibility techniques were learned from
[`danielealbano/android-remote-control-mcp`](https://github.com/danielealbano/android-remote-control-mcp)
(MIT), which is a far more feature-complete project — worth a look if you want
MCP instead of this minimal channel.
