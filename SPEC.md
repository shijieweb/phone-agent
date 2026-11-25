# phone-agent — SPEC (frozen contract)

A minimal Android agent that makes a phone controllable through our **existing**
`rc-relay` long-poll channel. Nothing on the VPS changes: the phone is simply a
fourth agent, exactly like `rc-agent.ps1` on Windows.

- **Package / namespace**: `net.de5.phoneagent`
- **minSdk 31 / targetSdk 34 / compileSdk 37**, JVM target 17
- **Zero third-party runtime dependencies.** Framework only:
  `java.net.HttpURLConnection`, `org.json`, `android.location.LocationManager`,
  `AccessibilityService`, `android.app.Activity` + views.
- The self-ADB stack (§3.16) is **vendored source** under
  `app/src/main/java/io/github/muntashirakon/`, not a dependency: the ADB protocol,
  SPAKE2 pairing and the curves compile into the APK from files in this repo.
  Attribution and every local change are in
  [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
- **No Kotlin plugin in `app/build.gradle.kts`** — AGP 9 has built-in Kotlin.
  Do not add `org.jetbrains.kotlin.android`; it is incompatible with the AGP 9 DSL.
- **No `vendor/`, no native code, no flavors, no signing config.** arm64 device,
  pure DEX APK.

---

## 1. Scope — exactly what to build

Ship these files (all under `app/src/main/kotlin/net/de5/phoneagent/`):

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Minimal config UI + permission requests + start/stop the agent |
| `Prefs.kt` | SharedPreferences wrapper (relay base URL, secret, agent id, auto-start, location interval) |
| `RcAgentService.kt` | Foreground service: hello → poll → execute → result loop |
| `RcClient.kt` | HTTP calls to `/agent/hello`, `/agent/poll`, `/agent/result` |
| `Commands.kt` | Parse one command line → `Command` sealed class |
| `Executor.kt` | Run a `Command` → `ExecutionResult(exit, stdout, stderr)` |
| `PhoneAccessibilityService.kt` | Accessibility service; exposes `instance` |
| `UiTree.kt` | Serialise the window node tree to compact text |
| `Gestures.kt` | tap / long press / swipe via `dispatchGesture` |
| `ScreenShot.kt` | Accessibility screenshot → downscaled JPEG → blob upload (base64 only on `b64`/fallback) |
| `LocationHelper.kt` | One-shot fix + periodic reporting |
| `BootReceiver.kt` | Restart the service after boot / app update |
| `Selector.kt` | Parse an element selector (`SEL`) — pure logic, no Android imports |
| `ElementActions.kt` | Element-level click / long click / scroll / IME enter |
| `Wake.kt` | Layered screen wake (`wake` command) |
| `WakeActivity.kt` | One-shot translucent Activity used by the third `wake` layer |
| `LoopGuard.kt` | Per-instance generation counter that keeps worker threads honest |
| `Battery.kt` | Doze / battery-whitelist state (`opt`) |
| `SelfAdb.kt` | Self-ADB transport: mDNS discovery, pairing, connect, one shell call (Android glue) |
| `SelfAdbKeys.kt` | RSA key pair + hand-written DER self-signed certificate, on disk in `filesDir/adb` |
| `SelfAdbPlan.kt` | Self-ADB pure logic: sentinel parsing, clamps, every user-facing string (no Android imports) |

Deliver `AndroidManifest.xml`, `res/values/strings.xml` and
`res/xml/accessibility_service_config.xml` **exactly as already written** — they
exist in this repo; do not rewrite them.

**Out of scope (do not build):** MCP server, HTTP server, WebSocket, OAuth,
camera, file manager, notifications, sharing, app install/uninstall, offline
geocoding, IP database, any AndroidX/Compose/Ktor/Hilt usage, root, and any
**third-party runtime dependency** — the one vendored exception is the self-ADB
stack in §3.16, which is source-merged into this repo and listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

Shell execution exists as of v0.5.0, but only through that one channel: the app
talks to **its own** adbd over loopback (§3.16), never to a PC and never with
root.

---

## 2. Relay contract (already live — do not change)

Base URL default: `https://dsh.2-c.cc:19092` — a direct route on the operator's
own paid domain, no Cloudflare in front (see the note in [README.md](README.md)
about why every `de5.net` name this app used to ship — `dsh.owen1.de5.net:19092`
and `mcp.owen1.de5.net` — is reset in transit on the operator's mobile carrier).

Three addresses are **retired defaults** and must migrate to the current one when
found in storage, because the app itself wrote them on first run:
`dsh.owen1.de5.net:19092` (shipped up to v0.2.0), `mcp.owen1.de5.net`
(shipped v0.3.0–v0.6.0) and `relay.2-c.cc` (shipped v0.6.1). Anything the
operator typed is left alone.

All three calls are `POST` with header `X-RC-Secret: <secret>` and a JSON body.
The secret is a 64-char hex string, configured in the UI, never hard-coded.

```
POST /agent/hello
  {"id":"PHONE-MEIZU","hostname":"...","user":"...","os":"...","ver":"0.1.0","ip":"","env":"..."}
  -> {"ok":true,"id":"PHONE-MEIZU","server_time":1760490000}

POST /agent/poll
  {"id":"PHONE-MEIZU","wait":25}
  -> {"cmd":{"cid":"...","cmd":"shot","created":1760490000.1,"timeout":120}}
  -> {"cmd":null}                      # nothing queued within `wait`

POST /agent/result
  {"id":"PHONE-MEIZU","cid":"...","cmd":"shot","exit":0,"dur":0.42,
   "user":"meizu","stdout":"...","stderr":"..."}
  -> {"ok":true}

PUT /agent/blob/<name>                # binary, not JSON; see §4
  header X-RC-Secret, body = raw bytes, exact Content-Length
  -> {"ok":true,"name":"shot-...","bytes":123456}

GET /ctl/blob/<name>                  # the controller fetches it back
```

Rules the client MUST honour:

- `id` must match `^[A-Za-z0-9_.-]{1,64}$`. Default `PHONE-MEIZU`, editable.
- `wait` is seconds; the server clamps it to 25. Use `wait = 25`, and set the
  HTTP **read timeout to 45 s** (must exceed the long poll).
- `cmd: null` is normal — immediately poll again.
- **At-most-once delivery**: once a command is delivered it is removed from the
  queue. If the process dies mid-execution the command is lost, and that is by
  design. Never re-execute a command you already acted on.
- `stdout` / `stderr` are truncated server-side to 200 000 characters each.
  **Keep `stdout` under ~190 000 chars** (see §4 for screenshots).
- Execution failures are reported in the *body* (`exit`, `stderr`), not by HTTP
  status. Always send a result, even on error.
- Retry policy: on network exception, sleep 3 s and retry the **same** call;
  after 5 consecutive failures sleep 30 s. Never crash the loop. Re-send
  `/agent/hello` after any reconnect gap.
- `hello` is also the heartbeat (`last_seen`). The server marks an agent offline
  after 90 s: send `hello` at least every 60 s **even if polling is otherwise
  blocked** (e.g. while the accessibility service is off).

### `env` field
Free-form, keep it short and machine-readable, e.g.
`android=16;model=meizu22;acc=on;bat=87;scr=on;opt=on`.

`opt` = Doze/App-Standby exemption (battery whitelist) granted. It is carried in
`env` because it is the one flag that decides whether the agent survives the
screen going off: without it the OS freezes the long poll and the relay records
the phone as offline (measured gaps of 138 s, 151 s and one of 9705 s).

---

## 3. Command grammar

One command per `cmd` string, single line, ASCII, no quoting constructs.
Parsing is whitespace-split; **arguments after the command name are positional**.

A line starting with `#rc:` is not shell grammar: it is the managed PC's
built-in action protocol and is handled before parsing (see §4.1). It must never
reach the `unknown command` branch.

| Command | Effect |
|---|---|
| `ping` | stdout `pong` |
| `status` | one-line JSON (see below) |
| `shot [b64] [maxPx] [quality]` | screenshot, stdout = one-line blob JSON (see §4). `b64` forces the legacy base64 stdout |
| `tree [maxNodes]` | indented node tree, default `maxNodes=400`, cap 800 |
| `tap X Y` | tap at ints X,Y |
| `long X Y [ms]` | long press, default 600 ms, cap 5000 |
| `swipe X1 Y1 X2 Y2 [ms]` | swipe, default 300 ms, cap 3000 |
| `key NAME` | global action: `back`, `home`, `recents`, `notifications`, `quicksettings` |
| `click [apply] SEL` | **dry run** by default: reports the node it would press; `apply` really clicks (see §3.1) |
| `longclick [apply] SEL` | same, long press |
| `scroll up\|down [n] [SEL]` | element-level scroll, `n` times (default 1, cap 20) |
| `ime enter` | send `ACTION_IME_ENTER` to the focused editable node |
| `wake` | turn the screen on and try to clear a non-secure keyguard |
| `text STRING` | type `STRING` into the focused field (rest of line, verbatim) |
| `apps` | list launchable apps, one per line: `<package>\t<label>` |
| `app PACKAGE` | launch the app's main activity |
| `loc` | one location fix, stdout = one-line JSON |
| `locloop INTERVAL` | start periodic reporting every `INTERVAL` s (min 30, max 3600) |
| `locstop` | stop periodic reporting |
| `stop` | stop the agent service (result is sent first) |
| `install URL` | download to cache, then open the system installer on it (see §3.3) |
| `uninstall PACKAGE` | open the system uninstall confirmation for `PACKAGE`; a package that is not installed is already done |
| `confirm [apply] [t=SECONDS] TEXT` | **dry run** by default; `apply` waits for a *pressable* node whose text/desc contains `TEXT` and presses it (see §3.2) |
| `settings [get\|put] global\|secure\|system KEY [VALUE]` | read or write a system setting |
| `logs [lines]` | logcat tail, default 120, cap 500 |
| `admin status` | authority report: Device Owner, admin, each development permission, installer app-op, accessibility |
| `admin release` | drop Device Owner and remove ourselves as device admin |
| `a11y status` | read-only: master switch, list entry, live binding, last rebuild — and the one repair that fits (see §3.15) |
| `a11y heal` | inspect, then repair: append a missing entry, or rescue a listed-but-unbound one |
| `a11y rescue` | force the full re-bind: switch off → delete the list → 2 s → write back → switch on |
| `lock` | lock the screen (needs an active device admin) |
| `reboot` | reboot — always exit 6: needs the signature-level `REBOOT` permission, which a sideloaded app cannot hold (see §3.5) |
| `adbinfo` | read-only wireless-debugging report: `adb_wifi_enabled`, wlan IP, mDNS port, `connect` string, 200 ms TCP probe (see §3.4) |
| `adbheal [t=SECONDS]` | turn wireless debugging on if it is off, wait up to `t` s (default 20, max 60) for the port, then report the same fields as `adbinfo` |
| `dl [t=SECONDS] URL [NAME]` | download `URL` into `/sdcard/Movies/[NAME]`, then media-scan it; timeout default 600 s, cap 3600 (see §3.6) |
| `files ls [PATH] [n]` | list one directory inside `/sdcard`, default 100 entries, cap 500 (see §3.7) |
| `files cat PATH` | print a text file, refusing > 64 KiB or binary (see §3.7) |
| `files info PATH` | type, size and modification time of one entry (see §3.7) |
| `files mkdir PATH` | create a directory and its parents (see §3.7) |
| `files rm [apply] PATH` | **dry run** by default: names the path, its size and the `apply` line; `apply` deletes (see §3.7) |
| `files find PATH KEYWORD [n]` | case-insensitive name search under `PATH`, default 50 hits, cap 200 (see §3.7) |
| `files df` | total / used / free and the used percentage of `/sdcard` (see §3.7) |
| `files pull PATH` | upload one file out through the blob channel, 16 MiB cap (see §3.7) |
| `notif [list] [n] [q=TEXT]` | the active notifications, default 30, cap 200, optional case-insensitive filter (see §3.8) |
| `say [-l zh\|en] [rate=PCT] [pitch=PCT] TEXT` | speak `TEXT` once through the system TTS engine; `zh` default (see §3.9) |
| `screen stayon [on\|off]` | keep the panel awake across reboots; with no argument it only reports (see §3.14) |
| `screen timeout MS` | one explicit panel timeout, clamped 15000..2147483647 (see §3.14) |
| `screen on` / `screen off` | light or darken the panel now (see §3.14) |
| `anim [get]` | the three animation scales (see §3.11) |
| `anim set on\|off\|SCALE` | write all three scales; `off` is what makes the UI snap (see §3.11) |
| `anim save` / `anim restore` | remember / write back the three scales (see §3.11) |
| `sms [list [n]]` | the inbox for the last 7 days, newest first, default 20, cap 100 (see §3.12) |
| `sms send [apply] NUMBER TEXT` | **dry run** by default; `apply` really sends (see §3.12) |
| `call [apply] NUMBER` | **dry run** by default; `apply` opens `ACTION_CALL` (or `ACTION_DIAL` without `CALL_PHONE`) (see §3.13) |
| `call answer` / `call hangup` / `call state` | call control and the current call state (see §3.13) |
| `call speaker on\|off` | speakerphone for the current call (see §3.13) |
| `shinfo` | read-only self-ADB report: key fingerprint, connected or not, loopback address, last note (see §3.16) |
| `sh [t=SECONDS] [apply] COMMAND` | **dry run** by default; `apply` runs `COMMAND` as uid 2000 through our own adbd, timeout default 30 s, cap 300 s, stdout capped at 8000 chars (see §3.16) |
| `shpair 123456` | pair with this phone's own adbd using the six-digit code from 无线调试, then try one connect (see §3.16) |
| `shconnect` | connect to this phone's own adbd; pairing only swaps keys, connecting is a separate step (see §3.16) |
| `shkey [regen [apply]]` | print the public key and its `SHA256:` fingerprint; `regen` is a dry run, `regen apply` really replaces the key (see §3.16) |

Anything else: `exit=2`, stderr `unknown command: <name>`.

### 3.2 `confirm`: only pressable labels count

`confirm` exists so a script can press the system installer's button on a phone
nobody is holding. A label is **not** enough to act on:

- a candidate must be `clickable`, have a clickable ancestor, or have a class
  containing `Button` (pressed by a gesture at its centre);
- a matching node that is a plain `TextView` with no clickable ancestor is
  counted as **inert** and never clicked — a wrong tap on an unknown screen is
  worse than an honest timeout;
- when several pressable candidates carry the label, the system installer's wins
  (`com.android.packageinstaller`, `com.google.android.packageinstaller`,
  `com.android.permissioncontroller`).

**`confirm` is a dry run too**: without `apply` it takes *one* look (no polling —
waiting only makes sense when a tap follows) and prints
`dry-run: would confirm "<text>" via=… pkg=… node=… at=x,y` with `exit=0`. A miss
reports `只匹配到不可点文本（N 个），没有可按的按钮` or `没有出现含「TEXT」的节点`,
both `exit=5`.

Success line of `confirm apply`: `confirmed "<text>" via=action|ancestor|gesture pkg=<PKG> node=<label> at=x,y attempts=N`.

Two distinct timeouts, because they mean different things:

| stdout/stderr | Meaning |
|---|---|
| `confirm 超时：Ns 内没有出现含「TEXT」的节点（尝试 N 次）` | the text never showed up — the script is looking at the wrong screen |
| `confirm 超时：Ns 内「TEXT」只匹配到不可点文本（N 个），没有可按的按钮` | the text is there but is not a button — wrong label, e.g. `安装` vs `继续安装` |

### 3.3 `install` / `uninstall`: privileged permissions, honestly

`INSTALL_PACKAGES` and `DELETE_PACKAGES` are `signature|privileged` with **no**
`development` flag: a sideloaded app cannot get them, `pm grant` is refused, and
**Device Owner does not help**. So there is no silent path and none is pretended:

1. `install URL` downloads the APK to `cacheDir/apk`, verifies the `PK` header, and
   starts the system installer on a read-only `content://` URI (`ApkProvider`);
2. it returns `exit=0` with `已拉起安装确认页（需确认安装）: <file>`;
3. `confirm 安装` (or `继续安装` on some ROMs) shows what it would press;
   `confirm apply 安装` presses the button.

`uninstall PACKAGE` uses `ACTION_DELETE` the same way, and treats "not installed"
as success so a retried script converges.

### 3.4 Wireless debugging: `adbinfo` / `adbheal`

The wireless-debugging port **changes on every reboot** and is not stored in
`settings` (`adb_wifi_port` does not exist — measured: null), while the pairing
survives reboots. So the only thing that ever has to be rediscovered is the
port, and the mDNS advertisement `_adb-tls-connect._tcp` is the only source of
truth for it.

Success (exit 0), one line per field:

```
adb_wifi_enabled = 1
本机 IP = 192.168.0.108
无线调试端口 = 38181（来源 _adb-tls-connect._tcp）
connect = 192.168.0.108:38181
TCP 探测 = 可连
```

There is deliberately **no exit code for "not found"**: a missing broadcast is a
真实 answer, not a failure, so it is exit 0 with `无线调试端口 = 未发现` and a
`说明` line naming the reason. Degradations are spelled out, never patched over:

| Situation | stdout |
|---|---|
| no broadcast resolved | `无线调试端口 = 未发现`, `connect = 无（未发现端口）`, `TCP 探测 = 未做（没有端口）`, `说明 = 未发现 _adb-tls-connect 广播（本机自解析可能被 ROM 过滤；也可能是无线调试未开启）` |
| broadcast found, nothing listening | `TCP 探测 = 不可连（200ms 内没有完成握手）` — this is a firewall/port problem, not a discovery one |
| wlan address unavailable | `本机 IP = 未取到本机 IP`; the port is still reported, the connect string becomes `未取到本机 IP:<port>` |
| `adb_wifi_enabled` unreadable | `adb_wifi_enabled = (null)` |
| write refused (`adbheal`) | exit 6, stderr carries `Admin.setSetting`'s own `WRITE_SECURE_SETTINGS` grant line |

### Two-tier discovery

The advertisement is a fact about the network, but NsdManager is not a reliable
way to hear it. Measured on the test tablet (Lenovo, Android 12): right after the
wireless-debugging toggle is flipped the port resolves on the first try, and then
for minutes at a time every `adbinfo` returns nothing — four consecutive misses
over minutes, while `adb mdns services` on the PC resolved the same broadcast on
every attempt, `adb_wifi_enabled` still read `1`, and logcat was empty. A silent
miss, not an exception.

So discovery runs two tiers, in order:

1. **NsdManager** — the system resolver. Cheapest when it works, and kept because
   nothing beats it at validating the advertisement.
2. **Raw mDNS query** — a PTR question for `_adb-tls-connect._tcp.local` built and
   sent by this app over a `MulticastSocket`, then SRV + A parsed back into
   `host:port` (`Mdns.kt` builds and parses the wire format, `RawMdns.kt` sends it).
   `RAW_RESERVE_MS = 2500` is held out of the NsdManager budget so tier 2 always
   has time to run.

Tier 2 sends from an ephemeral port and never binds 5353, so it never fights the
system's own mDNS daemon: the QU (unicast-response) bit is set on the first query,
and a second plain query relies on RFC 6762 §6.7 (a query from a port other than
5353 is answered by unicast).

Both tiers failing is reported as both failing, each with its own reason:

```
说明 = NsdManager 未发现（在 1995ms 内没有解析到 _adb-tls-connect._tcp 广播（本机自解析可能被 ROM 过滤）），裸 mDNS 查询也未收到应答（裸 mDNS 查询 2500ms 内没有收到应答）
```

The source line always says which tier answered:
`无线调试端口 = 41277（来源 _adb-tls-connect._tcp（NsdManager））` or
`（来源 _adb-tls-connect._tcp（裸 mDNS 查询））`.

### `adbheal` bounces the toggle

`adbheal` **unconditionally** writes `adb_wifi_enabled = 0`, waits
`BOUNCE_OFF_MS = 1500`, writes `1`, then discovers — even when the setting
already reads `1`. That is the only way to make the advertisement resolvable
again on the test tablet: it only resolves in the seconds right after the
daemon restarts.

**The cost is explicit: this drops any live `adb` session to the device.** That is
acceptable for a command whose entire purpose is "I lost the port, get it back" —
an operator who runs `adbheal` has no working session to lose. `adbinfo` never
writes the setting, so a plain query can never disconnect anyone.

### Cache

A discovered port is cached for 10 minutes and advertised in the heartbeat `env`
as `adbwifi=<port>`, so `rc-ctl -Action List` shows it without running a command.
A stale cache is omitted entirely — the relay never sees a port that has moved.

- `adbheal` always re-learns (it just restarted the daemon, so the cached port is
  about to be wrong) and overwrites the cache on success.
- `adbinfo` reports a fresh cache directly, with `来源 = 缓存（Ns 前发现）`, so
  repeated queries do not each cost a discovery round.
- `adbinfo` with a stale cache adds `缓存已过期（Ns 前发现…）；建议跑 adbheal`.
- `adbinfo` with the daemon off does not query at all: it reports
  `说明 = 无线调试未开启（adb_wifi_enabled=0）；跑 adbheal 打开并发现端口`.

A port that resolves but refuses TCP is reported as exactly that —
`说明 = 端口已发现但 TCP 不可连（可能被防火墙拦，或守护进程刚重启）` — because a
listening-socket problem is a different fault from a discovery problem.

Discovery needs `CHANGE_WIFI_MULTICAST_STATE` (a normal permission, declared)
because many ROMs drop mDNS without a multicast lock.

### Unattended: boot heal and heartbeat observation

Nobody picks the tablet up at 3 a.m., so the port is rediscovered without a command:

- **Boot heal** (`BootHeal.kt`, called from `BootReceiver`). ~20 s after
  `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED` — long enough for Wi-Fi to associate —
  a background `HandlerThread` reads `adb_wifi_enabled` and writes `1` **only if
  it is not already `1`**, then discovers and caches the port. It never bounces the
  toggle: boot has no live session worth preserving, but it also has no reason to
  kill one. Failure only writes an `AgentLog` line under tag `adb`; the boot path
  never throws and never blocks `onReceive`.
- **Heartbeat observation** (`RcAgentService.observeAdbPort`). The heartbeat looks again
  **read-only** — it writes no setting — when the cache is missing or older than
  `AdbReport.OBSERVE_STALE_MS = 30 min`, and at most once per
  `ADB_OBSERVE_INTERVAL_MS = 10 min` with an 8 s budget. 30 min, not 10: 10 min is
  when the `adbwifi=` field disappears, and re-discovering on the very first beat
  after that would spend network on every heartbeat forever. A bounce here is
  forbidden outright — the heartbeat runs every 15 s, so a bounce would cut every
  adb session on the phone once a minute.

Both paths re-entry-guard: `BootHeal` with a process-wide `AtomicBoolean`, the
heartbeat with its own timestamp. Both only ever *fill* the cache, so a working
port is never replaced by a worse answer.


### 3.5 `reboot`: both doors are locked

There are exactly two ways for an app to reboot a device, and a sideloaded
build can use neither:

1. `PowerManager.reboot` needs `android.permission.REBOOT`, whose
   `protectionLevel` is `signature|privileged`. That is platform signing — not
   grantable by `pm grant` either, because `pm grant` only works on permissions
   carrying the `development` flag. The same wall blocks `INSTALL_PACKAGES` and
   `DELETE_PACKAGES` (§3.3).
2. `DevicePolicyManager.reboot` is the owner-only door. It works without the
   permission, but it needs the Device Owner role, and an app can only become
   Device Owner during provisioning — i.e. on a factory-reset device, before any
   account is added. This build deliberately never asks for that (§3.3 keeps the
   admin receiver as a fallback only).

So `reboot` always answers `exit=6` with
`错误: 重启需要系统签名权限（REBOOT），侧载应用无法申请；平板已设 DO 也不够，只能手动重启或 root`
— naming the real obstacle instead of a role the operator could chase forever.
`lock` is different and does work: `DevicePolicyManager.lockNow` only needs an
active device admin, which the operator can grant by hand.

### 3.6 `dl`: put a file where a player can see it

The only file operation this agent performs, and the reason is domestic rather than
technical: someone in the family wants to watch a film on the tablet, and the
operator is not holding it.

    dl <url> [name]
    dl t=300 <url> [name]

- Destination is **always** `/sdcard/Movies/<name>` — one directory, no path
  argument, so no command can write anywhere else.
- `name` is the rest of the line verbatim (spaces allowed) and is cleaned by
  `DownloadPlan.sanitizeName`: only the last path segment survives (`../../etc/passwd`
  → `passwd`), control characters, whitespace and `: * ? " < > |` become `_`, a
  leading `.` or `-` is stripped (hidden files do not show up in galleries), and
  anything over 96 chars is truncated with the extension kept. A name that cleans
  down to nothing falls through to the URL guess.
- No `name`: the last URL path segment, if it looks like a file with an extension
  and is not a query string (`?X-Sig=…` is dropped first). Otherwise
  `download-<epochMillis>.mp4`.
- Success, `exit=0`:

      `已下载 <文件名> <字节数> 字节 → /sdcard/Movies/<文件名>`

- Failure goes to stderr and carries what already landed, so a retry is decidable:

      `错误: 下载失败: <原因>（已写 <n> 字节）`

  Reasons: `HTTP <code>` (any non-2xx), `文件超过 2048MB`, `超过 <t> 秒没有下完`,
  `已有下载在进行`, or the framework's own message.
- Exit codes: timeout or cancellation `4`, remote/HTTP problem `5`, local write
  problem (including "another download is running") `6`, usage `2`.
- Only one transfer at a time (`AtomicBoolean`), so two queued `dl`s cannot
  interleave writes.

**Why MediaStore and not `File("/sdcard/Movies")`.** From Android 10 a plain write
into a public directory needs `MANAGE_EXTERNAL_STORAGE`, which is a special access
toggle a *person* must grant in system settings — unacceptable for an unattended
device. MediaStore needs no permission at all because we own the row we insert, and
the visible path is the same `/sdcard/Movies/<name>`. The row is inserted with
`IS_PENDING = 1` and published (`IS_PENDING = 0`) only after the last byte, so a
half-written file never shows up in a gallery or in VLC. The collection is chosen by
extension — Video, Audio, or Downloads — because VLC's library reads the video
collection even though the path is identical either way.

**Streaming, caps and aborts.** The body is copied in 64 KB chunks (a 1 GB film in
a `ByteArray` is an instant OOM on a phone that is also running a foreground
service), and every chunk is checked against `MAX_BYTES = 2 GB`; crossing it throws
*before* the disk fills. The transfer runs on its own daemon thread while the poll
thread waits on a `CountDownLatch` for at most `t` seconds, so the long poll is
never blocked inside a network read. On timeout the worker's stream is closed, the
connection disconnected, and the worker's own `finally` deletes the pending row —
the caller reports the byte count it had written rather than pretending the file
exists.

**Media scan.** When the extension is one VLC opens by itself (`mp4 mkv avi mov
webm m4v 3gp ts flv wmv mpg mpeg` and the audio set), the finished file is passed
through `MediaScannerConnection.scanFile`, so "VLC → Internal storage → Movies"
lists it without a reboot. The MediaStore row alone is usually enough; the scan is
for players that consult the legacy media index.

**The family scenario, end to end:** `dl https://…/film.mp4` → exit 0 with
`已下载 film.mp4 <n> 字节 → /sdcard/Movies/film.mp4` → open VLC on the tablet →
Internal storage → Movies → tap it. No permission step, no USB cable.

### 3.7 `files`: read and clean up inside /sdcard only

```
files ls [PATH] [n]      files cat PATH      files info PATH
files mkdir PATH         files rm [apply] PATH
files find PATH KEYWORD [n]      files df      files pull PATH
```

- **The only tree any verb may touch is `/sdcard`** (`/storage/emulated/0` and
  `/storage/self/primary` are the same tree and are accepted spellings).
  `FileGuard.resolve` resolves `..` **lexically, before** the containment
  check, so `/sdcard/../etc/passwd` is refused as an escape rather than handed
  to the filesystem. A NUL byte is refused. A relative path is taken relative
  to `/sdcard` (`files ls DCIM` means `/sdcard/DCIM`).
- A path that escapes or cannot be used answers `exit=2`, stderr
  `路径不可用: <path>（只允许 /sdcard 内的路径）`.
- `ls` prints a header stating how many entries the directory really has —
  `<path> (N 项)`, plus `，只显示前 K 项` when truncated — then one row per
  entry: `d|-` + size + `yyyy-MM-dd HH:mm` + name (`/` suffix for a directory).
  Directories first, then case-insensitive name. Default `n=100`, cap 500.
  A trailing **pure integer** is the count, so a directory literally named
  `2024` still resolves (`files ls 2024` lists it, `files ls DCIM 20` counts).
- `cat` refuses more than 64 KiB (`CAT_MAX_BYTES`) and refuses a file
  containing a NUL byte (binary); both answer `exit=2` with the reason.
- `rm` is **dry run by default**: it prints the absolute path and the size and
  the exact `files rm apply PATH` line that would delete it. `apply` deletes
  recursively. A root (`/sdcard` itself) is refused unconditionally.
  A missing `MANAGE_EXTERNAL_STORAGE` app-op surfaces as `exit=6` naming the
  `appops set` line, never as "file not found".
- `mkdir` succeeds when the directory already exists.
- `find` walks `PATH` (default `/sdcard`) depth-first and keeps entries whose
  **name** contains `KEYWORD`, case-insensitively. A trailing pure integer is the
  hit cap, so the last non-numeric token is the keyword and everything before it
  is the path — which is why `files find /sdcard/My Documents doc 5` works.
  `/sdcard/Android/data` and `/sdcard/Android/obb` are never entered (the
  platform denies them to a non-system app, so walking them only burns time), and
  a symbolic link is reported as itself rather than followed — `/sdcard` contains
  links back into itself and following one would loop until the scan cap.
  The scan stops at 60 000 entries and says so
  (`（已达到扫描上限，结果可能不完整）`); the answer is otherwise complete for the
  tree it did walk. An empty keyword matches nothing rather than everything:
  "list every file on the card" is `files ls`, not a search.
- `df` reads `StatFs` on `/sdcard` and answers one line:
  `总 116.4 GB 已用 88.1 GB 可用 28.3 GB (76%)`. Percent is rounded and clamped
  to 0..100; an unreadable volume answers 0% instead of a division by zero.
- `pull` is the only verb that moves bytes **out** of the phone: it reads the file
  (refusing a directory, an empty file, and anything over `ShotBlob.MAX_BYTES`),
  uploads it through the same relay blob channel screenshots use, and answers one
  JSON line `{"name":"file-<millis>-<suffix>.<ext>","bytes":N,"path":"…"}`. The
  extension is preserved (lower-cased, `a-z0-9` only, max 8 chars) so the server
  side can name the download; a name that would violate the relay's
  `^[A-Za-z0-9_.-]{1,80}$` rule loses its extension rather than failing. Without
  a relay client the answer is `exit=6` and says the blob channel is missing —
  not "file not found".

### 3.8 `notif`: the active notifications

```
notif [list] [n] [q=TEXT]
```

- Backed by a `NotificationListenerService` (`NotifListenerService`), which
  only sees anything after the operator grants notification access:
  `adb shell cmd notification allow_listener
  net.de5.phoneagent/net.de5.phoneagent.NotifListenerService`
  (or 设置 → 通知使用权 by hand).
- One line per notification: `- pkg | title | text`, a leading `*` instead of
  `-` when the notification is ongoing. Newlines are flattened and runs of
  whitespace collapse to one space, because a multi-line chat body would
  otherwise break the one-line-per-notification shape. Title clipped to 80
  chars, body to 160.
- Default `n=30`, cap 200. `q=` is a case-insensitive substring over package,
  title and body; the query keeps its inner spacing (`notif q=微信 消息` searches
  the two-word phrase).
- With the listener not connected the answer is `exit=0` and a sentence that
  contains the exact grant line — an operator who has not run it yet has
  nothing to fix in the app. No match at all answers `没有匹配的通知`.

### 3.9 `say`: speak one sentence

```
say [-l zh|en] [rate=PERCENT] [pitch=PERCENT] TEXT
```

- `TEXT` is the rest of the line **verbatim** (inner spaces kept). Leading
  flags are consumed before the text; a sentence that merely *contains*
  `-l en` keeps it (`say hi -l en` speaks "hi -l en" in Chinese).
- `-l` / `--lang` / `-l=en` all work. Two voices only: `zh` (default) and
  `en`. Every spelling of the wish lands on one of them (`zh`, `zh-CN`,
  `zh_Hans`, `cn`, `中文`, `汉语` → `zh`; `en`, `en-GB`, `English`, `英文`,
  `英语` → `en`). An unknown language is **refused** (`exit=2`) rather than
  silently falling back: reading English in a Chinese voice sounds like a
  broken phone, not a wrong flag.
- `rate` is a percentage of normal speech, clamped 20..400 (default 100);
  `pitch` clamped 50..200 (default 100). Out-of-range values are clamped, not
  rejected — the operator asked for "faster", not for an error.
- `TEXT` is clipped to 2000 chars. The executor waits
  `chars/4 + 5` seconds, clamped 5..120, then answers whether the utterance
  finished; a stuck engine is a reported timeout, never a hung poll loop.
- The system TTS engine is used (no bundled voice). A missing voice data pack
  answers with the path to install it (设置 → 语言和输入 → 文字转语音).
- A new engine instance is created and shut down per utterance: a cached engine
  keeps a permanent service binding, which is exactly how an agent ends up
  "silent after a week".

### 3.10 `admin status`: the extended-permission section

`admin status` keeps its existing lines and appends a `扩展权限:` block:

- one `<PERMISSION>=已授予|未授予` line for each permission `pm grant` can
  actually hand over (`READ_SMS`, `SEND_SMS`, `READ_PHONE_STATE`,
  `READ_CALL_LOG`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_CONTACTS`,
  `RECORD_AUDIO`, `READ_CALENDAR`, `ACCESS_FINE_LOCATION`);
- `所有文件访问`, `悬浮窗`, `勿扰权限`, `通知使用权`, each with the adb line that
  turns it on when it is off.

The point is that every one of these has a *different* door (runtime grant,
app-op, or a settings screen the user must tap), and the operator should not
have to guess which.

### 3.11 `anim`: the three animation scales

```
anim [get] | anim set on|off|SCALE | anim save | anim restore
```

- The keys are `window_animation_scale`, `transition_animation_scale`,
  `animator_duration_scale` in `Settings.Global`. They are **floats stored as
  strings** and stay strings end to end: re-encoding `"0.5"` through a Float
  would rewrite a value the operator set by hand.
- `set` accepts `on` (`"1"`), `off` (`"0"`) or a number in 0..10, normalised to
  one decimal (`0.5`). Out of range or non-numeric → `exit=2`.
- The write goes through `WRITE_SECURE_SETTINGS` (the same door as
  `settings put`), not through `SET_ANIMATION_SCALE`, which is
  `signature|privileged` and is declared in the manifest only for completeness.
  A refusal answers `exit=6` with the `pm grant` line.
- `save` remembers the three current values in prefs; `restore` writes them
  back and refuses with a plain sentence when nothing was ever saved.

### 3.12 `sms`: the inbox, and one guarded send

```
sms [list [n]] | sms send [apply] NUMBER TEXT
```

- `list` reads `content://sms` for the **last 7 days only** (cap 90), newest
  first. One line per message: `< address body` for received, `> address body`
  for sent. Body clipped to 200 chars. Default `n=20`, cap 100.
- Without `READ_SMS` the answer is `exit=6` naming the exact `pm grant` line,
  and it never pretends the inbox is empty.
- `send` is **dry run by default**: it prints the number and the exact body and
  the `sms send apply …` line that would send it. The body goes to a real person
  and cannot be recalled, so the operator sees it first.
- The number is normalised at **parse time** (spaces, dashes and parentheses
  stripped, a leading `+` kept, at least 3 digits) so a typo never reaches the
  telephony stack. The body keeps its inner spacing verbatim.
- Multi-part bodies go through `sendMultipartTextMessage`. `SEND_SMS` is
  checked before anything is sent.

### 3.13 `call`: dial, answer, hang up

```
call [apply] NUMBER | call answer | call hangup | call state | call speaker on|off
```

- `call NUMBER` is **dry run by default** — a real phone rings on the other end,
  which is irreversible in the only way that matters.
- With `CALL_PHONE` granted, `apply` uses `ACTION_CALL`; without it,
  `ACTION_DIAL` (which only opens the dialer) and the answer says so with the
  `pm grant` line.
- `answer` uses `TelecomManager.acceptRingingCall()`, which needs
  `ANSWER_PHONE_CALLS` or `MODIFY_PHONE_STATE`; `hangup` uses `endCall()`.
- `state` reports `空闲 / 响铃中 / 通话中`. Ringing needs `READ_PHONE_STATE`;
  without it only the audio mode is available and the line says so.
- `speaker on|off` sets `AudioManager` speakerphone (needs
  `MODIFY_AUDIO_SETTINGS`). `on` switches the audio mode to
  `MODE_IN_COMMUNICATION` **before** raising the speaker flag, because the flag is
  ignored while the mode is still `MODE_NORMAL`; `off` clears the flag first and
  then returns the mode to `MODE_NORMAL`, so the earpiece is not left routed to a
  mode nothing is driving. `call speaker` with no argument is the report form:
  `免提: 开|关（通话中|当前无通话）`, and it never changes anything. An unknown
  value (`call speaker maybe`) is `exit=2` rather than a guess — silently picking
  `on` would put a call on the loudspeaker in a meeting.
- **What this can never do:** carry the call audio itself. Call audio travels
  through the modem and the audio HAL; a sideloaded app cannot read it —
  `CAPTURE_AUDIO_OUTPUT` is signature-level. `call` controls the *call*, it
  does not listen to it.

### 3.14 `screen`: keep the panel awake, and turn it on or off

```
screen stayon [on|off] | screen timeout MS | screen on | screen off
```

- `screen stayon on` writes `Settings.System.SCREEN_OFF_TIMEOUT = 2147483647`
  ("never") **and** stores `stay_on = true` in prefs; `off` restores 60000 (the
  Android default). `screen stayon` with no argument is the **report** form:
  `常亮: 开|关 当前超时=…`, so it never changes anything.
- The prefs flag exists because the ROM rewrites `screen_off_timeout` on every
  boot: `BootReceiver` re-applies it **before** the auto-start check and
  independently of whether the relay is configured — the reason to set it (a
  phone that must answer at 3 a.m.) has nothing to do with the relay.
- `screen timeout MS` writes one explicit timeout, clamped 15000..2147483647,
  and clears `stay_on` — a hand-typed timeout is the newer decision, so a
  reboot must not put "never" back.
- `screen on` lights the panel (`PowerManager` + `ACQUIRE_CAUSES_WAKEUP`); if
  the ROM refuses a background wake it answers `已请求亮屏，但屏幕仍是熄的`
  instead of claiming success. `screen off` goes through the device-admin lock
  (`lockNow`), because `PowerManager.goToSleep` needs the signature-level
  `DEVICE_POWER`; the sentence says it also locked.
- Every write needs `WRITE_SECURE_SETTINGS`; a refusal is `exit=6` with the
  `pm grant` line.
- The same switch is a checkbox in the app (`屏幕常亮`), which calls the same
  code and then re-reads the stored flag, so a refused write leaves the box
  unchecked rather than lying.

### 3.15 `a11y`: the three facts that fail independently

```
a11y status | a11y heal | a11y rescue
```

- Accessibility breaks in three separate places, and each has its own repair:
  the **master switch** (`Settings.Secure.ACCESSIBILITY_ENABLED`), our **entry**
  in `enabled_accessibility_services`, and the **live binding**
  (`PhoneAccessibilityService.instance`). A phone can be listed and switched on
  and still bind nothing, which is exactly the state a fresh install leaves
  behind — every `tree`/`click` then answers "accessibility service not
  connected" while every setting looks correct.
- `status` is read-only and always `exit=0`; it prints the three facts, one
  `最近自愈:` line and one `结论:` line that names the repair for the state it
  found: `正常` / `条目缺失，执行 a11y heal 或 <adb line>` /
  `总开关被关闭，执行 a11y heal` /
  `条目在但服务未绑定，执行 a11y rescue 强制重绑`. A missing entry outranks a dead
  binding, because "toggle the component" cannot work when there is no component
  to toggle.
- `最近自愈:` quotes the newest `[a11y]` line from the in-app log (timestamp
  included) so the rebuild is auditable from the phone alone. When nothing has
  been logged in this process it prints `(本次进程内无记录)` rather than an empty
  value — the v0.4.2 bug was invisible precisely because "never ran" rendered as
  nothing at all.
- `heal` inspects first and then runs the repair that fits: a **missing entry**
  is appended (every other entry kept), a **listed entry with a dead binding** is
  sent to the rescue path. `heal` therefore never answers "无障碍条目已存在" and
  stops — that answer was the v0.4.2 dead end.
- `rescue` always does the full re-bind, for the case where the operator has
  already read `status` and decided. The sequence is the only one that works on
  Flyme/Android 16: `accessibility_enabled = 0` → **delete**
  `enabled_accessibility_services` → wait 2 s → write the list back → `= 1`.
  Writing an empty string instead of deleting is refused by the system
  (`Bad arguments`), which is why v0.4.2's reconnect silently did nothing. Other
  accessibility services are preserved in their original order, and our own
  component is never duplicated.
- **After every reinstall the entry must be rebuilt.** Installing a new APK keeps
  the secure setting but drops the binding, and Android rewrites the setting a few
  seconds later. There are **three triggers**, and only the first is guaranteed:

  | Trigger | Fires | Cost when healthy | Why it is here |
  |---|---|---|---|
  | **Process start** — `A11yHeal.checkNow` from `RcAgentService.onCreate` and `MainActivity.onCreate` | every time our process comes up | two setting reads + one log line | The process *always* starts; a broadcast may never arrive. This is the one that actually repairs the Meizu. |
  | **Package replaced** — `BootReceiver` on `MY_PACKAGE_REPLACED` **and** `PACKAGE_REPLACED` (with the `package` data scheme, matched against our own package name) | only on ROMs that deliver them | one log line | Cheap, and correct where it works. Retried at 3 s / 10 s / 30 s because Android rewrites the setting a few seconds *after* the broadcast. |
  | **Heartbeat** — `selfHealAccessibility` | every 5 min | nothing (throttled, reads two settings) | Last resort. It is what quietly saved v0.4.3 after ~15 s, which is how we learned the broadcast never came. |

  All three share one rule (`A11yStartup.plan` → heal / rescue / nothing / skip)
  and one single-repair lock, so a service start and an Activity start cannot run
  two interleaved `delete → 2 s → write` sequences over the same setting. The
  rebuild is **only** on a replacement, never on a plain boot: Android restores
  the entry itself on boot, and writing it then would silently override an
  operator who turned accessibility off on purpose.
- **Flyme does not deliver `MY_PACKAGE_REPLACED`.** Measured on the Meizu
  (Flyme AIOS 12.7 / Android 16): `adb logcat -c`, install v0.4.3 through the
  system installer, wait 50 s → the only `[a11y]` lines were the *heartbeat's*
  (`自愈: 已强制重连无障碍服务（保留原有 0 条）`), and grepping `换包|BootReceiver|MY_PACKAGE`
  across `PhoneAgent:I` and `ActivityManager:I` returned nothing at all, while
  `dumpsys package` showed the receiver enabled with the filter intact. The
  broadcast is simply never sent to us. Do **not** try to fix this through the
  ROM's autostart whitelist: that is a system setting we cannot change and should
  not change. The fix is the first row of the table above — hang the check on our
  own process start, because the process always starts and a broadcast may not.
- Both the rebuild and the heartbeat self-heal first check
  `Admin.canWriteSecure`; without `WRITE_SECURE_SETTINGS` they log the skip and
  return instead of retrying a refusal every 15 s.

### 3.16 `sh` / `shpair` / `shconnect` / `shkey`: our own adbd, over loopback

**What this is.** The app connects to **its own** `adbd` (the phone's wireless
debugging daemon) on `127.0.0.1`, pairs with it, and runs commands as uid 2000.
No PC, no USB, no root, no third-party binary: the ADB protocol, the SPAKE2
pairing and the RSA key encoding are vendored source in this repo (see
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)) and compiled into the APK.

```
shinfo
sh [t=SECONDS] [apply] COMMAND
shpair 123456
shconnect
shkey | shkey regen [apply]
```

**Why loopback only.** `127.0.0.1` is a constant, not a parameter. Connecting to
a LAN IP would need the same code plus a network permission, would break every
time Wi-Fi changes, and would put the phone's adbd on the wire. There is
deliberately **no** `sh HOST` form.

**`sh` is a dry run by default.** This is the single most destructive command in
the whole surface — it can `rm -rf` `/sdcard` as uid 2000 — and it is also the
one most likely to be replayed from a log. So:

| Form | Effect |
|---|---|
| `sh ls /sdcard` | exit 0, stdout `dry-run: would run sh: ls /sdcard` — nothing runs |
| `sh apply ls /sdcard` | really runs it |
| `sh t=120 apply id` | really runs it, 120 s budget (default 30, clamped 1..300) |
| `sh echo apply` | `apply` is part of the payload here, so this is still a **dry run** of `echo apply` |

Flags are only eaten **before** the payload: the first non-flag token starts the
command and everything from there on is passed through byte for byte (spaces,
quotes and pipes included). `sh apply` with no command is a usage error.

**Exit codes are split, on purpose.** A remote exit code of 4 must never look
like our own `EXIT_TIMEOUT`:

| Situation | Our `exit` | Where the remote code goes |
|---|---|---|
| command ran | 0 | `stdout` ends with `exit=N` |
| not paired / not connected | 6 | the sentence names the next command to run |
| no result within `t` | 4 | stderr `命令超时（Ns 内没有返回）` |
| ran, but the sentinel never came back | 4 | stdout is the raw text, no `exit=` line |

**How the remote exit code is recovered.** The `shell:` service multiplexes
stdout and stderr into one stream and the protocol carries no exit status, so the
command is wrapped as `COMMAND` + newline + `echo __RC=$?`. The parser takes the
**last** `__RC=<digits>` whose remainder is blank, strips it, and returns the
number. Anything else (no sentinel, trailing output, non-digit) yields "no exit
code" and the raw text is returned as-is — the agent never invents a 0. A command
whose own text contains `__RC=` is therefore safe: only the final echo counts.

**Output cap.** `stdout` is capped at 8000 chars and the truncation is stated in
the text (`… 输出被截断（原 N 字符，上限 8000）`). The relay truncates at 200 000,
so this cap is ours, chosen so one `sh` cannot flood the poll channel.

**Pairing.**

1. Phone: 设置 → 系统 → 开发者选项 → 无线调试 → **使用配对码配对设备** → six digits.
2. `shpair 123456` — the app finds the `_adb-tls-pairing._tcp` port over mDNS,
   runs SPAKE2 against it, writes the key, then tries one `shconnect`.
3. `shconnect` alone is enough afterwards; the port changes but the pairing
   survives reboots.

The key lives in `filesDir/adb/adbkey` (PKCS#8 PEM) with `adbkey.pub` beside it,
2048-bit RSA with `e=3` — the same shape AOSP's `adb keygen` produces, so the
fingerprint in `shkey` can be compared character by character against 手机
「已配对的设备」. The certificate is self-signed and generated in-process
(hand-written DER): ADB only requires that the key and certificate match, and
Android exposes no certificate-issuing API.

**mDNS is unreliable, so discovery has three tiers**: the system `NsdManager`
callback → a raw mDNS query → toggling `adb_wifi_enabled` off/on (needs
`WRITE_SECURE_SETTINGS`) and retrying. A missing port answers
`没发现 adb-tls-pairing 端口：确认无线调试已打开，等 3-5 秒重试（部分 ROM 会过滤 mDNS 广播）`
— it never silently degrades into "connected but broken".

**`shkey`** prints `公钥: <one line>` and `指纹: SHA256:<base64 without padding>`.
`shkey regen` only prints
`dry-run: 会删除现有密钥并重新生成，配对关系将失效，确认请加 apply`;
`shkey regen apply` deletes both files, generates a new pair, and clears the
process-wide TLS context — without that last step the old `KeyPair` stays pinned
in the cached `SSLContext` and the symptom is "pairing succeeds but connecting
fails".

**What it still cannot do.** No root (`id` reports `uid=2000(shell)`); no
`pm grant` for signature permissions (the shell user cannot hand those out
either); no writing to another app's private storage. It is the same authority
`adb shell` has over USB — nothing more.

### 3.1 Element selectors (`SEL`)

The whole rest of the line after the command name is the selector, **verbatim**
(spaces included, same treatment as `text`). Grammar:

```
SEL := [kind:]value
kind := text | desc | id | cls        (optional; default = any of text/desc/id)
```

- Matching is **substring** and **case-insensitive**.
- Without a `kind:` prefix, `value` is tested against `text`, `contentDescription`
  and `resourceId`. With a prefix, only that one field.
- `id:foo` also matches a full id such as `com.meituan.android:id/foo` by substring.
- Walk the node tree **depth-first, pre-order, across all windows** (same
  enumeration as `tree`) and take the **first** match.
- If the matched node is not itself clickable, walk **up** to the nearest clickable
  ancestor before acting.
- If the node is not `isVisibleToUser`, first try
  `performAction(ACTION_SHOW_ON_SCREEN)` and re-read the bounds.
- **Default is a dry run.** `click`/`longclick`/`confirm` read the tree, report
  what they *would* press, and press nothing. One stray tap on a phone nobody is
  holding can dismiss the very dialog the script was about to confirm, so the tap
  is opt-in with the `apply` keyword. Dry-run `stdout`:
  `dry-run: would <click|longclick|confirm> "<TEXT>" via=action|ancestor|gesture pkg=<PKG> node=<label> at=x,y`,
  `exit=0`, `stderr` = `matches=<n>`. A dry-run miss says what is wrong in plain
  words (`没有出现含「SEL」的节点`, or `只匹配到不可点文本（N 个），没有可按的按钮`)
  with `exit=5` — the same conditions the `apply` form reports, minus the timeout
  it never waited for.
- `apply` is recognised only as a leading token, before the selector/label:
  `click apply 登录`, `confirm apply t=30 安装`. `tap`/`long`/`swipe` take explicit
  coordinates and are unaffected — they always act.
- Attempt order for `click apply`: `ACTION_CLICK` on the node (or its clickable
  ancestor) → if that returns false, fall back to a gesture tap at the **centre of
  the node's bounds**. Report which path was used.
- Attempt order for `longclick apply`: `ACTION_LONG_CLICK` → gesture long press at
  the centre (default 600 ms).
- `stdout` on success: `clicked via=action|ancestor|gesture node=<resId|text> at=<x>,<y>`
  (the label is `resId` if present, otherwise the first 40 chars of `text`, then
  `desc`). `longclick` prints `longclicked …` with the same shape.
- `stderr` always carries `matches=<n>` so the operator can see ambiguity.
- `apply` and no match → `exit=5`, stderr `no node matched: <SEL>`; a dry run and
  no match → `exit=5`, stderr `没有出现含「SEL」的节点`.
- `SCROLL` needs `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD`; if no
  scrollable node is found → `exit=5`, stderr `no scrollable node`.
  `stdout`: `scrolled down x<n> node=<resId|text>`.

### 3.2 `wake`

Layered, because no single mechanism is reliable on every ROM. Report which layer
worked so the operator can tell an OS restriction from a bug:

1. If `PowerManager.isInteractive()` is already true → `stdout`
   `wake already-on`, `exit=0`.
2. Otherwise acquire `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` (deprecated
   since API 17 but still functional; `WAKE_LOCK` is already declared), hold ~1 s,
   release, re-check.
3. If still off, start the translucent `WakeActivity`
   (`setTurnScreenOn(true)` + `setShowWhenLocked(true)`, then `finish()`), wait
   ~1.5 s, re-check.
4. If the screen is now on and `KeyguardManager.isKeyguardLocked()` and
   **not** `isKeyguardSecure()` → `requestDismissKeyguard`.
5. `stdout`: `wake ok via=wakelock|activity scr=on kg=locked|none` — or
   `wake failed via=none scr=off kg=locked` with `exit=4`.
   A secure keyguard can never be cleared silently: report `kg=secure` and still
   return `exit=0` if the screen came on.

Being blocked by background-activity-start restrictions is a **reported
outcome**, not a crash: `wake` must never throw.

`status` stdout (one line):
```json
{"ok":true,"ver":"0.1.0","acc":true,"svc":true,"scr":true,"bat":87,"loc":true,"loop":0,"opt":"on","queue":0}
```
`acc` = accessibility service connected, `svc` = agent service running,
`scr` = screen interactive, `loc` = location permission granted,
`loop` = periodic location interval in seconds (0 = off),
`opt` = `"on"` when the app is exempt from battery optimisation, else `"off"`.

### Exit codes
| Code | Meaning |
|---|---|
| 0 | success |
| 2 | bad usage / unknown command |
| 3 | accessibility service not connected |
| 4 | timeout (gesture not completed, location fix timed out) |
| 5 | not found (package, node, focus target) — also the `#rc:` action-failed code (blob upload refused) |
| 6 | permission denied |
| 7 | interrupted (service stopping) |

---

## 4. Screenshot encoding (the one tricky part)

The relay carries **text only** and truncates `stdout` at 200 000 chars, so a
full-size screenshot cannot travel on stdout. Two paths exist:

**Blob path (default since v0.3.0)**

- Capture via `takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`.
- On success: `Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)`,
  then **close the hardware buffer immediately**, then scale down.
- Scale so the **longest side ≤ `maxPx` (default 720, clamp 320..1440)**, encode
  `JPEG` at quality `q` (default 55, clamp 20..90) — one attempt, no ladder: a
  blob has no 200 000 char ceiling, so shrinking further only costs quality.
- `PUT {relay}/agent/blob/<name>` with header `X-RC-Secret: <secret>`, body = the
  raw JPEG, exact `Content-Length` (`setFixedLengthStreamingMode`),
  `Content-Type: application/octet-stream`, connect/read timeouts 30 s / 60 s.
  Name: `shot-<epochMillis>-<6 random [a-z0-9]>`, matching
  `^[A-Za-z0-9_.-]{1,80}$`; the relay caps one blob at 16 MB.
- Success `stdout` = **one line of JSON, no `| ` prefix**:
  `{"name":"shot-…","bytes":N,"w":W,"h":H,"q":Q,"method":"screen"}` (≤300 chars).
  `stderr` = `w=<w> h=<h> bytes=<n> blob=<name>`. `exit=0`.
- Any upload failure **must not cost the screenshot**: the already-encoded JPEG is
  base64’d in place (no second capture — that would race the platform rate
  limiter), `stdout` = base64, and `stderr` gains
  `blob upload failed: <reason>，已回退 base64`. Only if that base64 would still
  exceed 185 000 chars does it fall back to the ladder below.

**Base64 path (`shot b64 …`, and the automatic fallback)**

- `stdout` = the base64 string on **one line, no wrapping, no data-URI prefix**.
- `stderr` = `w=<w> h=<h> bytes=<n> b64=<len>`. If the encoded base64 would
  exceed 185 000 chars, **re-encode smaller** (halve `maxPx`, then drop quality
  to 35) and report what was used in `stderr`; if it still does not fit, fail
  with `exit=2` and stderr `screenshot too large`.
- Report the 5 screenshot error codes distinctly:
  `ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR`, `..._NO_ACCESSIBILITY_ACCESS`,
  `..._INTERVAL_TIME_SHORT`, `..._INVALID_DISPLAY`, `..._INVALID_WINDOW`.
  `INTERVAL_TIME_SHORT` must map to `exit=4` and stderr
  `screenshot rate-limited (INTERVAL_TIME_SHORT)`. **Do not retry in a tight
  loop** — the platform enforces a minimum interval between captures.
- Always `bitmap.recycle()` after encoding.

### 4.1 `#rc:` built-in action protocol (controller side)

The managed PC (`scripts/rc.py`) does not send `shot`; it sends
`#rc: shot {"width":1280,"quality":72}` and then reads the `name` field of the
one-line JSON to fetch the bytes over HTTP. Grammar: `#rc: VERB [JSON]`.

- The line is split off **before** the shell grammar: a `#rc:` verb must never
  fall through to `unknown command`.
- `shot`: `width` → `maxPx` (default 720, clamp 320..1440), `quality` (default 55,
  clamp **10..95** — wider than the human `shot` window, because the controller
  may legitimately ask for 10). `hwnd`/`rect` are ignored silently: Android has no
  window handles. Answer = the same one-line JSON as the blob path.
- **No base64 fallback here.** The controller parses the JSON, so an unexpected
  base64 payload would look like a corrupt reply. Upload failure answers
  `{"ok":false,"err":"blob upload failed: <reason>"}` with `exit=5`.
- Any other verb (`wins`, `uia`, `act`, …) answers
  `{"ok":false,"err":"未知内置动作：<verb>"}` with `exit=2` — never executed, never
  a silent `exit=0`.
- A malformed `#rc:` line (no verb, unbalanced JSON) answers
  `{"ok":false,"err":"bad #rc payload: <detail>"}` with `exit=2`.
- Lines without the prefix keep every old behaviour.

---

## 5. Node tree format (`tree`)

One node per line:
```
<depth>|<class>|<text>|<desc>|<resId>|<bounds>|<flags>
```
- `depth` = nesting level, 0 for each window root.
- `text`, `desc`, `resId` truncated to 60 chars each, `\n`/`|` replaced by `/`.
- `bounds` = `l,t,r,b`.
- `flags` = concatenated letters, only those true:
  `c`=clickable `l`=long-clickable `f`=focusable `e`=editable `s`=scrollable
  `x`=checked `d`=disabled `k`=selected.
- Skip nodes that have no text, no contentDescription, no resource id **and** no
  flags — unless they have children (walk through them without emitting a line).
- Enumerate **all** windows via `service.windows` (needs
  `flagRetrieveInteractiveWindows`), starting each at depth 0; fall back to
  `rootInActiveWindow` if `windows` is empty.
- If the tree is larger than `maxNodes`, stop and append a final line
  `... truncated at <n> nodes`.
- Accessibility service not connected → `exit=3`, stderr `accessibility service not connected`.

---

## 6. UI (`MainActivity`)

One screen, `android.app.Activity`, plain `LinearLayout` built in code (no XML
layout file). Fields:

- Relay base URL (`EditText`, default `https://relay.2-c.cc`)
- Agent id (`EditText`, default `PHONE-MEIZU`)
- Secret (`EditText`, `inputType=textPassword`)
- `保存` button → persists to SharedPreferences
- `启动 / 停止` button → starts/stops `RcAgentService`, and **writes the
  auto-start flag in the same press**: stopping sets `auto_start = false`
  *before* the service is touched, starting sets it `true`. The decision itself
  is `StartPolicy.onToggle` (§7), so the button and `BootReceiver` can never
  disagree. Without this, "stop" was a suggestion: a reboot, an app update or
  `START_STICKY` would bring the agent back on a phone nobody was watching.
- `开机自动启动` checkbox → binds `Prefs.autoStart`, written on every change; the
  button updates it too, and the box is re-synced on refresh so it never shows a
  stale value
- `屏幕常亮` checkbox → the same switch as `screen stayon` (§3.14). It calls
  `ScreenOps.stayOn` — never a second write path — and then re-reads
  `Prefs.stayOn`, so a refused write leaves the box unchecked instead of showing
  a state that is not in force.
- A hint line under them (`StartPolicy.HINT`): 停止后不会自动启动，除非重新点「启动」；
  勾选「开机自动启动」时，重启或装新版会自己拉起。
- `请求权限` button → requests `POST_NOTIFICATIONS`,
  `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, then (second pass)
  `ACCESS_BACKGROUND_LOCATION`
- `打开无障碍设置` button → `ACTION_ACCESSIBILITY_SETTINGS`
- `加入电池白名单` button → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with
  `package:<applicationId>`; if the ROM refuses the intent, fall back to
  `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
- `打开电池优化设置` button → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
- A `TextView` status line refreshed every 2 s from a static snapshot held by
  `RcAgentService` (service state, accessibility on/off, last poll time, last
  error), plus the current battery-whitelist state. Never do network I/O on the
  UI thread.

Buttons must not crash when the service was never started.

---

## 7. Service behaviour

- `RcAgentService` is started with `startForegroundService`. In `onStartCommand`:
  build a notification channel (`phone-agent`, `IMPORTANCE_LOW`), call
  `startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`.
- `START_STICKY`, and `onTaskRemoved` → schedule a restart via `AlarmManager`
  (1 s) so swiping the app away does not kill the agent.
- Hold a **partial `WakeLock`** while the poll loop runs (`10 min` timeout,
  renewed each iteration); release it in `onDestroy`. Guard with try/catch.
- The poll loop runs on a dedicated `Thread` (or `HandlerThread`), never the main
  thread. All accessibility calls must run on the **main thread**.
- **"Stop" must mean stop.** `BootReceiver` starts the service only when
  `StartPolicy.shouldAutoStart(prefs.autoStart, prefs.isConfigured())` is true, and
  the stop button clears `auto_start` first. There is exactly one rule, in
  `StartPolicy`, used by both sides and unit-tested (`StartPolicyTest`).
- The heartbeat (every `HEARTBEAT_CHECK_MS = 15 s`) also drives two self-heals, both
  throttled and both best-effort: accessibility (`selfHealAccessibility`, 5 min
  cadence) and the wireless-debugging port (`observeAdbPort`, 10 min cadence,
  read-only, only when the cache is 30 min stale — §3.4). Neither may block the
  heartbeat or throw into it; both log to `AgentLog` and return. The
  accessibility heal checks `Admin.canWriteSecure` first and skips with one log
  line when the grant is missing, so a phone without it does not accumulate a
  refusal every 15 s (§3.15).
- **A reinstall rebuilds the accessibility binding**, through three independent
  triggers so that no single missing signal leaves the phone unable to answer
  `tree`: `onCreate` runs `A11yHeal.checkNow` (guaranteed — the process must
  exist for anything to work), `BootReceiver` schedules `A11yHeal` on both
  `MY_PACKAGE_REPLACED` and `PACKAGE_REPLACED` (retried at 3 s / 10 s / 30 s),
  and the heartbeat covers the rest at a 5 min cadence. Flyme delivers neither
  broadcast — see §3.15 for the measurement. None of this is part of the boot
  path: on a plain boot Android restores the entry itself.
- `stop` command → send the result, then `stopSelf()`.
- Read config from `Prefs` **once at start**; if the URL or secret is blank,
  stop immediately and surface `未配置 relay 地址或口令` in the UI status.

---

## 8. Accessibility service

- `PhoneAccessibilityService : AccessibilityService`, `companion object { @Volatile var instance: PhoneAccessibilityService? = null }`, set in
  `onServiceConnected`, cleared in `onDestroy`.
- Do **not** call `setServiceInfo` at runtime — the XML config is authoritative.
- `onAccessibilityEvent` / `onInterrupt`: no-op (log only). We read the tree on
  demand; there is no cache.
- `takeScreenshot` availability: only call it when `instance != null`.

---

## 9. Periodic location

- `locloop N` starts a `HandlerThread`-based repeater: every `N` seconds obtain
  one fix and push it with
  `POST /agent/result {"cid":"loc-<epochMillis>","cmd":"loc","exit":0,...}` and
  `stdout` = the same one-line JSON as `loc`. A synthetic `cid` is intentional:
  unsolicited results appear in the relay's per-agent history, which is how the
  operator reads the trail.
- One-shot `loc`:
  `LocationManager.getCurrentLocation(provider, null, executor, consumer)` with a
  10 s timeout, falling back to `getLastKnownLocation`, preferring
  `GPS_PROVIDER` then `NETWORK_PROVIDER` then `PASSIVE_PROVIDER`.
- JSON: `{"lat":39.9,"lon":116.4,"acc":12.0,"ts":1760490000,"prov":"gps","spd":0.0,"bear":0.0}`
  (the phone reports coordinates only — reverse geocoding happens server-side).
- No permission → `exit=6`, stderr `location permission not granted`.
- No fix at all → `exit=4`, stderr `no location fix`.

---

## 10. Tests (required, must run on a plain JVM)

Put pure-logic tests in `app/src/test/kotlin/` using JUnit 4 (`junit:junit:4.13.2`,
already in `dependencies`). **Only test logic that does not touch the Android
framework** — do not mock `AccessibilityService`. Required cases:

1. `Commands.parse`: every command in §3, argument defaults, clamping
   (`maxPx`, quality, durations, `locloop` bounds), `text` keeps the rest of the
   line verbatim including spaces, unknown command → exit 2. Plus the v0.3.0
   additions: `shot b64`, the leading `apply` token on `click`/`longclick`/
   `confirm` (before or after `t=`), and a selector that legitimately starts with
   the word `apply` (disambiguated by a `kind:` prefix).
2. Screenshot size guard: given a byte count, the re-encode ladder picks the
   expected step and reports it.
3. Node-tree formatting: given a small fake node model, the emitted lines match
   the format in §5 (truncation, `|` escaping, flag letters, depth).
4. Relay URL joining: base URL with/without a trailing slash produces exactly
   `/agent/hello` etc. (no double slash).
5. Location JSON serialisation field names and rounding.
6. `#rc:` line parsing (pure, no `org.json` — write a tiny structural check, do
   not add a parser dependency): verb extraction, a missing verb, a
   truncated/`not-json`/array payload, an unknown verb, and the `width`/`quality`
   clamps — including a payload that is structurally fine but carries no number.
7. Blob naming/JSON: the name matches `^[A-Za-z0-9_.-]{1,80}$`, the suffix is
   sanitised and padded to 6 chars, and the one-line JSON has the field order
   `name,bytes,w,h,q,method` and stays under 300 chars.
8. Dry-run line: `via=action|ancestor|gesture` is chosen from the inspection
   (no clickable target → `gesture`, clickable ancestor → `ancestor`), and
   `at=?` when the node has no usable bounds.
9. The v0.4.0 device-surface plans, each a pure object proven on a plain JVM:
   `FileGuard` (lexical `..` resolution, escape refusal, the three root
   spellings, size/time formatting, listing truncation), `NotifPlan`
   (newline flattening, clipping, the `*` ongoing marker, filtering),
   `SayPlan` (every language spelling, unknown language → null, rate/pitch
   clamps, `waitSeconds` bounds), `ScreenPlan` (the `Int.MAX_VALUE` sentinel,
   the 15 s floor, the `永不`/`60s`/`ms` renderings, the report line),
   `SmsPlan` (number normalisation, body clipping), `AnimPlan` (scale
   normalisation, one-decimal formatting), `CallPlan` (number normalisation,
   dry-run wording).
10. The v0.4.0 grammar in `Commands.parse`: `files` verb dispatch and the
   trailing-count rule, `notif` limit/`q=`, `say` leading flags vs a payload
   that merely contains one, `screen` all four sub-commands and their refusals,
   `sms`/`call` dry-run defaults, and `rec` (removed in v0.4.0) landing on
   `unknown command` rather than some other verb.
11. The v0.4.2 additions, same split: `FindPlan` (case-insensitive name match, an
    empty keyword matching nothing, the limit clamp, the two skipped trees across
    all three root spellings, stable path ordering, the truncated and scan-capped
    sentences), `DfPlan` (rounding, the 0% guard on an unreadable volume, no
    negative sizes), `PullPlan` (extension extraction and its refusals, suffix
    sanitising, every generated name passing `ShotBlob.isValidName`, the 16 MiB
    sentence, JSON escaping), `A11yPlan` (all four verdicts and the precedence of
    a missing entry), and the grammar: `files find` with and without a trailing
    count, a path containing spaces, `files df` refusing arguments, `files pull`
    verbatim, `call speaker` report/on/off/synonyms and its refusal of anything
    else, `a11y status` vs `a11y heal` vs `a11y rescue`.
 12. The v0.4.3 repairs, both of which failed silently before: `BootPlan` (the
     step list keeps `RebuildAccessibility` when `autoStart=false` — the v0.4.2
     rebuild never ran because the auto-start gate returned early — while
     `StartService` alone stays gated; a plain boot never rebuilds; `stayOn`
     restores the timeout; the full order) and `A11yOps` (a fake `SecureSink`
     proves the list is **deleted** rather than overwritten with an empty
     string, that the master switch goes 0 -> 1 around the write, that the 2 s
     sleep is actually requested, that other services keep their order, and
     that `repairAction` sends a listed-but-unbound service to the rescue).
 13. The v0.4.4 process-start trigger: `A11yStartup.plan` over all four states
     (healthy → nothing, entry missing → heal, listed-but-unbound → rescue, no
     `WRITE_SECURE_SETTINGS` → skip) including the case where a live binding
     outranks the missing grant; `BootPlan` returning `RebuildAccessibility` for
     **both** package-replaced actions and for neither on a plain boot; and
     `A11yPlan.annotate` labelling each trigger `[装包]`/`[启动]`/`[心跳]` — with
     the install spelling tested before the generic one, since `安装后自愈`
     contains `自愈` and a wrong order would call every install repair a heartbeat.
14. The v0.5.0 self-ADB layer, all on a plain JVM: `HkdfSha256` against **RFC 5869
    appendix A case 1 and case 3** (case 3 is the null-salt path ADB pairing
    actually uses, and it is the one a hand-written HKDF gets wrong); the sentinel
    parser over every shape — clean `exit=0`, non-zero (`ls /nope` → 1), command
    text that itself contains `__RC=` (only the last one counts), no sentinel,
    trailing output after the sentinel, and CRLF input; the timeout clamp
    (1..300 s) and the six-digit pairing code; the 8000-char output cap stating the
    original length; base64 round-trip plus the `SHA256:` fingerprint shape; and
    the exception-to-sentence map (`AdbPairingRequiredException` → "run shpair",
    `AdbAuthenticationFailedException` → "run shkey regen apply"). `ShCommandsTest`
    pins the grammar: `apply`/`t=` are only eaten before the payload,
    `sh echo apply` stays a dry run, and `shkey regen` alone never replaces the key.

**Design for testability**: keep `Commands.parse`, the size ladder, the tree
formatter's string building, URL joining and JSON building in classes/functions
that take plain data (no `Context`, no `AccessibilityNodeInfo`). Wrap
`AccessibilityNodeInfo` in a tiny `NodeView` interface so the formatter is pure.

---

## 11. Definition of done

- `./gradlew assembleDebug` succeeds on a clean GitHub runner (that is the only
  build we have — there is no local Android SDK).
- `./gradlew test` passes with the §10 tests, **0 failures** (421 cases at
  v0.5.0-pad12).
- `manualcheck.md` documents, for each command, the exact phone-side action that
  proves it works (screenshot of the phone, tap landing on the right target,
  location matching Google Maps, …). CI cannot prove this; a human does.
