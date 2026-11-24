# phone-agent — SPEC (frozen contract)

A minimal Android agent that makes a phone controllable through our **existing**
`rc-relay` long-poll channel. Nothing on the VPS changes: the phone is simply a
fourth agent, exactly like `rc-agent.ps1` on Windows.

- **Package / namespace**: `net.de5.phoneagent`
- **minSdk 33 / targetSdk 34 / compileSdk 37**, JVM target 17
- **Zero third-party runtime dependencies.** Framework only:
  `java.net.HttpURLConnection`, `org.json`, `android.location.LocationManager`,
  `AccessibilityService`, `android.app.Activity` + views.
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

Deliver `AndroidManifest.xml`, `res/values/strings.xml` and
`res/xml/accessibility_service_config.xml` **exactly as already written** — they
exist in this repo; do not rewrite them.

**Out of scope (do not build):** MCP server, HTTP server, WebSocket, OAuth,
camera, file manager, notifications, sharing, app install/uninstall, offline
geocoding, IP database, any AndroidX/Compose/Ktor/Hilt usage, any shell command
execution.

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
| `a11y heal` | re-append our accessibility service to `enabled_accessibility_services` |
| `lock` | lock the screen (needs an active device admin) |
| `reboot` | reboot — always exit 6: needs the signature-level `REBOOT` permission, which a sideloaded app cannot hold (see §3.5) |
| `adbinfo` | read-only wireless-debugging report: `adb_wifi_enabled`, wlan IP, mDNS port, `connect` string, 200 ms TCP probe (see §3.4) |
| `adbheal [t=SECONDS]` | turn wireless debugging on if it is off, wait up to `t` s (default 20, max 60) for the port, then report the same fields as `adbinfo` |
| `dl [t=SECONDS] URL [NAME]` | download `URL` into `/sdcard/Movies/[NAME]`, then media-scan it; timeout default 600 s, cap 3600 (see §3.6) |

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
  heartbeat or throw into it; both log to `AgentLog` and return.
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

**Design for testability**: keep `Commands.parse`, the size ladder, the tree
formatter's string building, URL joining and JSON building in classes/functions
that take plain data (no `Context`, no `AccessibilityNodeInfo`). Wrap
`AccessibilityNodeInfo` in a tiny `NodeView` interface so the formatter is pure.

---

## 11. Definition of done

- `./gradlew assembleDebug` succeeds on a clean GitHub runner (that is the only
  build we have — there is no local Android SDK).
- `./gradlew test` passes with the §10 tests, **0 failures**.
- `manualcheck.md` documents, for each command, the exact phone-side action that
  proves it works (screenshot of the phone, tap landing on the right target,
  location matching Google Maps, …). CI cannot prove this; a human does.
