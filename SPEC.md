# phone-agent — SPEC (frozen contract)

A minimal Android agent that makes a phone controllable through our **existing**
`rc-relay` long-poll channel. Nothing on the VPS changes: the phone is simply a
fourth agent, exactly like `rc-agent.ps1` on Windows.

- **Package / namespace**: `net.de5.phoneagent`
- **minSdk 31 / targetSdk 34 / compileSdk 37**, JVM target 17
- **Zero third-party runtime dependencies.** Framework only:
  `java.net.HttpURLConnection`, `org.json`, `android.location.LocationManager`,
  `AccessibilityService`, `android.app.Activity` + views.
- Two **vendored source** trees, not dependencies, compile into the APK from files
  in this repo: the self-ADB stack (§3.16) under
  `app/src/main/java/io/github/muntashirakon/` (ADB protocol, SPAKE2 pairing, the
  curves) and, since v0.7.0-pad12, the jump3r LAME port (§3.20) under
  `app/src/main/java/de/sciss/jump3r/`. Attribution and every local change are in
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
| `BootReceiver.kt` | Repair the phone after boot / app update — it never starts the service (v0.6.3-pad12) |
| `Selector.kt` | Parse an element selector (`SEL`) — pure logic, no Android imports |
| `ElementActions.kt` | Element-level click / long click / scroll / IME enter |
| `Wake.kt` | Layered screen wake (`wake` command) |
| `WakeActivity.kt` | One-shot translucent Activity used by the third `wake` layer |
| `LoopGuard.kt` | Per-instance generation counter that keeps worker threads honest |
| `Battery.kt` | Doze / battery-whitelist state (`opt`) |
| `SelfAdb.kt` | Self-ADB transport: mDNS discovery, pairing, connect, one shell call (Android glue) |
| `SelfAdbKeys.kt` | RSA key pair + hand-written DER self-signed certificate, on disk in `filesDir/adb` |
| `SelfAdbPlan.kt` | Self-ADB pure logic: sentinel parsing, clamps, every user-facing string (no Android imports) |
| `Mp3Plan.kt` | Video→MP3 pure logic: which suffixes count, target names, durations, the scan report, the dry-run line, every progress/summary sentence (no Android imports) |
| `Mp3Converter.kt` | One video → one MP3: `MediaExtractor` + `MediaCodec` decode, `LameMp3Encoder` encode, written as `.part` then renamed |
| `Mp3Runner.kt` | The batch loop shared by `mp3 run apply` and the 视频转音乐 page: who is skipped, per-file timeout, progress lines, summary |
| `LameMp3Encoder.kt` | Thin Kotlin driver over the vendored jump3r LAME port — module wiring, `lame_init_params`, chunked encode, flush |
| `ConvertActivity.kt` | The owner's third screen: folder, counts, 开始转换, one progress line, the result list |
| `CamPlan.kt` | Camera pure logic: blob name, the scale ladder, the size picker over `SCALER_STREAM_CONFIGURATION_MAP`, the note JSON, every user-facing string (no Android imports) |
| `CameraActivity.kt` | The owner's fourth screen (v0.9.0-pad12): Camera2 preview, one box of text, one 发送 button — capture, shrink, upload, note |

Deliver `AndroidManifest.xml`, `res/values/strings.xml` and
`res/xml/accessibility_service_config.xml` **exactly as already written** — they
exist in this repo; do not rewrite them.

**Out of scope (do not build):** MCP server, HTTP server, WebSocket, OAuth,
file manager, notifications, sharing, app install/uninstall, offline geocoding,
IP database, any AndroidX/Compose/Ktor/Hilt usage, root, and any
**third-party runtime dependency** — the two vendored exceptions are the self-ADB
stack in §3.16 and the jump3r LAME port in §3.20, both source-merged into this
repo and listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

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

POST /agent/note                      # device-initiated, see §3.29
  header X-RC-Secret, Content-Type: application/json
  {"id":"PHONE-MEIZU","kind":"look","text":"...","blob":"cam-....jpg","w":1280,"h":720}
  -> {"ok":true,"seq":42}

GET /ctl/notes?since=<seq>&id=<id>&wait=<s>   # controller side, long poll
  -> {"notes":[{seq,kind,text,blob,w,h,ts}],"since":..,"seq":..}
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
| `tree [uia] [maxNodes]` | indented node tree, default `maxNodes=400`, cap 800. `uia` reads it as uid 2000 through `uiautomator` instead of the accessibility channel (see §5) |
| `tap X Y` | tap at ints X,Y |
| `long X Y [ms]` | long press, default 600 ms, cap 5000 |
| `swipe X1 Y1 X2 Y2 [ms]` | swipe, default 300 ms, cap 3000 |
| `key NAME` | global action: `back`, `home`, `recents`, `notifications`, `quicksettings` |
| `click [apply] [up] SEL` | **dry run** by default: reports the node it would press; `apply` really clicks; `up` also allows pressing a clickable ancestor when the node has no usable rect (see §3.1) |
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
| `stop` | stop the agent service (result is sent first). **Identical to pressing 停止**: it clears `auto_start` and writes the app-side stop marker `/sdcard/.phoneagent-stop` before `stopSelf()`, so the guard exits on its next beat even with no self-ADB (cellular), and neither a reboot nor a UI open can revive it (see §3.17) |
| `install URL` | download to cache, then open the system installer on it (see §3.3) |
| `uninstall PACKAGE` | open the system uninstall confirmation for `PACKAGE`; a package that is not installed is already done |
| `confirm [apply] [t=SECONDS] TEXT` | **dry run** by default; `apply` waits for a *pressable* node whose text/desc contains `TEXT` and presses it (see §3.2) |
| `settings [get\|put] global\|secure\|system KEY [VALUE]` | read or write a system setting |
| `logs [lines]` | logcat tail, default 120, cap 500. **Read as uid 2000 over self-ADB when it is connected** — on Android 16 the app's own uid triggers a system log-access dialog that only offers 「允许一次」 and re-appears every time, so the app-read path is a fallback, not the default (§3.19) |
| `logs size` | what the logs occupy, in three separate ledgers: the in-memory ring (`AgentLog`, cap 400), the guard log `/data/local/tmp/phoneagent-guard.log` (cap 64 KB, measured over self-ADB) and leftover APKs in `cacheDir` (§3.18) |
| `logs trim [apply]` | **dry run** by default; `apply` cuts the guard log back to its last 200 lines when it is over 64 KB and deletes cached APKs older than 24 h |
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
| `speak [voice=ID] [speed=0.5-2.0] TEXT` | speak `TEXT` through the VPS Fish TTS service, chunk by chunk; falls back to the system voice (see §3.30) |
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
| `shkey [show]` | print where the key came from (自生成 / 导入), the public key line and its `SHA256:` fingerprint (see §3.16) |
| `shkey regen [apply]` | `regen` alone is a dry run; `regen apply` really replaces the key (see §3.16) |
| `shkey import URL [PUBURL]` | download a ready-made key pair and install it; the way around the hidden-API block on Android 16 (see §3.16) |
| `shkey adopt` | not implemented: `/data/misc/adb/adb_keys` is adbd-private and `uid 2000` cannot read it on a `user` build (see §3.16) |
| `shdest DEST` | debug verb: open `DEST` verbatim, read up to 8 s, report bytes/hex/text. Always `exit 0` — the report is evidence, not a verdict (see §3.16) |
| `watchdog [status]` | is the uid-2000 guard alive, its pids, the last restart stamp, whether the stop marker exists (see §3.17) |
| `watchdog arm` / `watchdog disarm` | start / stop the guard by hand; `arm` refuses to start a second copy (see §3.17) |

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

#### The gate: a broadcast is not necessarily *our* broadcast (v0.6.4-pad12)

mDNS is a **shared** channel. Every device with wireless debugging on, on the same
Wi-Fi, advertises `_adb-tls-connect._tcp`. Measured on the test tablet
(Lenovo TB-J607F, Android 12) with the phone on the same network:

| Fact | Value |
|---|---|
| tablet's own adbd | `192.168.0.108:41081` (desktop `adb connect` succeeded) |
| what the tablet's discovery returned | `40157` |
| who really owns 40157 | the **phone**, `192.168.0.106` (`adb connect` refused, 10061) |
| symptom | `shconnect` stable `exit 4`, 「连接超时（30000ms 内没有结果）」 |

The upstream helper (`AdbMdns.java:112-128`) only checks that the port is bindable
on *some* local interface; it never asks where the advertisement came from, and the
old `nsdPort()` threw the callback's `host` away and returned the first port it saw.

So every candidate now passes `MdnsGate` (`MdnsGate.kt`), two gates in fixed order:

1. **Address** — the advertisement's address must equal this device's wlan IPv4
   (`AdbWifi.localIpv4`). Equal ⇒ pass. Different ⇒ drop. Either side unknown ⇒
   undecidable, go to gate 2.
2. **Loopback** — actually connect `127.0.0.1:port`. Our own adbd is always
   listening there (that is the address `SelfAdb` connects to); someone else's
   port never is. Harder evidence than string comparison.

When the address already decided, gate 2 is skipped: there is no second
possibility to pay a TCP round trip for. Discovery **keeps collecting** candidates
until the budget runs out instead of returning the first one, and a port is only
ever reported when it passed. **No candidate is ever "tried anyway"** — if nothing
passes, the answer is "not found" plus how many foreign advertisements were dropped:

```
没发现 adb-tls-connect 端口：确认无线调试已打开，等 3-5 秒重试（部分 ROM 会过滤 mDNS 广播）；已忽略 1 个其它设备的广播（如 192.168.0.106:40157）
```

In `adbinfo`/`adbheal` the count is folded into the existing `说明 = ` line rather
than added as a seventh line — the report format stays exactly six lines.

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
  boot: `BootReceiver` re-applies it on every boot and independently of whether
  the relay is configured — the reason to set it (a phone that must answer at
  3 a.m.) has nothing to do with the relay.
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
shkey | shkey show | shkey regen [apply]
shkey import URL [PUBURL]
shkey adopt
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

**Two destinations exist, and only one of them works on this ROM (v0.5.4).**
adbd dispatches an `OPEN` by its destination string, which must be
`<service>:<args>`. There are two ways to reach the shell service:

| Destination | adbd path | State |
|---|---|---|
| `shell:` (empty command) | `kPty` — interactive PTY | **default**, works everywhere |
| `shell:COMMAND` | `kRaw` — one-shot subprocess | broken on Flyme (Android 16) |

History, because it explains the design: v0.5.1 sent the bare command text as the
whole destination, adbd matched no service, answered `CLSE`, and every
`sh apply` failed with `ConnectException("Stream open actively rejected by remote
peer.")` while `shinfo` still said 已连接 — the socket was fine, the *request*
was malformed. v0.5.2 added the service prefix. v0.5.3 then measured what adbd
actually does with it, using the `shdest` debug verb, and got this on the
target device (all `exit 0`; the failure is inside the report):

| `shdest` argument | Result |
|---|---|
| `exec:id` | `IOException: Stream closed.` (0.0 s) |
| `shell:id` / `shell,raw:id` / `shell,v2,raw:id` / `shell,v2,pty:id` | same, 0.0 s |
| `shell,v2:true` | same, 0.0 s |
| **`shell:`** (no command) | **12 bytes: `Meizu22:/ $ `** — a real prompt |
| `sync:` | no data (the service opens; it just has nothing to say) |

So every destination that carries a command dies right after `OKAY`: this ROM's
`kRaw` subprocess path is broken, and the empty-command PTY is the only way in.
That is not a workaround specific to one phone — `kPty` is the POSIX-standard
path, and it is what a human sees in `adb shell` without a command.

**PTY execution, in three steps that must not be reordered.** The shell is
interactive now, so it has to be driven the way a person drives it:

1. **Wait for the prompt** (1.5 s budget, polled every 10 ms). Writing before
   the prompt arrives gets the command swallowed: the shell reports exit 0 with
   no output, which reads exactly like "the command did nothing".
2. **Write `COMMAND` + newline + the sentinel line.** A newline, not `;` —
   block syntax (`for` / `if` / `while`) survives a newline and is flattened
   by a semicolon.
3. **Read until the sentinel appears, then close.** A PTY never reaches EOF on
   its own; waiting for EOF is waiting for the timeout.

The sentinel is `echo "__RC=$?"` — **with quotes**. Bare `echo __RC=$?` prints a
lone `0` that cannot be told apart from the command's own output; quoted, it
prints one whole line `__RC=0`. The echo of that line is `echo "__RC=$?"`, which
contains `$?` but no digits, so it is discarded rather than misread.

**Parsing PTY output** is where the subtle bugs live, so the rules are explicit:

- strip ANSI escapes first — otherwise no line-start test works;
- strip a leading prompt, including Flyme's exit-code prefix
  (`127|Meizu22:/ $ `) — the prompt is glued to the first line of output;
- drop the echoed command line and the echoed sentinel line (that is our own
  typing coming back);
- accept **only** a standalone line `__RC=<digits>`, and take the **last** one;
- everything else is output, joined back with newlines, trailing blank lines
  removed.

A command whose own output contains `__RC=9` in the middle of a line is kept as
data. If the command prints a *standalone* `__RC=9` line it is genuinely
indistinguishable from the sentinel — that is the inherent cost of sentinels
(the protocol carries no exit status); taking the last occurrence still yields
the true code, because the sentinel is always last.

**stdout and stderr are merged, and this is a documented limitation.** The
`shell:` service multiplexes both into one stream. `sh` therefore reports
**merged output** in its `stdout` field, and the `stderr` field is reserved for our
own failures (not paired, timed out). `sh apply ls /data/misc/adb` proves it:
the `Permission denied` text arrives on stdout together with `exit=1`.

**Fallback to raw exists but is not the default.** If opening the PTY stream
itself throws, `SelfAdb` falls back to `shell:COMMAND` and flips the channel
flag. The order matters: on this ROM raw *always* fails, so trying it first
would burn one timeout per command. `shinfo` prints the channel actually in
use:

`@
通道: PTY（shell:）
通道: RAW（降级 shell:COMMAND）
`@

**`shdest DEST`** is the debug verb that produced the table above: it opens the
destination **verbatim** (no prefix is added — adding one would defeat the A/B),
reads for up to 8 s, and reports bytes, hex and printable text. It always exits
0, because its output is evidence, not a verdict.

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

**Why pairing needs an escape hatch (v0.5.1).** Pairing appends the TLS
exporter's key material to the PAKE password, and the only way to reach that
exporter is `Conscrypt.exportKeyingMaterial(SSLSocket, String, byte[], int)` —
a **non-SDK** interface. On Android 16 with `targetSdk 34`, hidden-API
enforcement makes `getMethod` throw `NoSuchMethodException` even though the
method exists, so the pairing handshake cannot complete. Connecting is
unaffected (it is plain TLS + RSA auth), which is why `shconnect` on an
already-authorised key still works.

The exporter is tried in three tiers before giving up — the Conscrypt static
4-arg form, an instance method on the `SSLSession`, then a scan of the
session's declared methods. If all three fail, the answer is a sentence that
names the next command, never a stack trace:

```
配对失败：本机系统禁止非 SDK 接口（exportKeyingMaterial）。请改用 shkey import <url> 导入一把已被手机授权的密钥。
```

**`shkey import URL [PUBURL]`** is the way around it: take a key pair that the
phone already trusts (`adb_keys` already contains its public half) and install
it into `filesDir/adb/`. Default `PUBURL` is `URL + ".pub"`. It needs **no
`apply`** — it does not tap the screen or delete user data, it only overwrites
this app's own key files — but the spec is explicit that it **replaces the
current key**, so any pairing made with the old one is gone.

Validation happens **before** anything is written, and the current key is left
untouched when validation fails:

| Check | Failure text |
|---|---|
| private key is PKCS#8 PEM | `导入失败：私钥不是 PKCS#8 PEM（需要 -----BEGIN PRIVATE KEY----- 开头）` |
| private key parses as PKCS#8 | `导入失败：私钥无法解析（需要 PKCS#8 的 RSA 私钥）` |
| modulus/exponent readable | `导入失败：这把私钥不是 RSA 私钥，或者 PKCS#8 结构不认识` |
| public key decodes to 524 bytes | `导入失败：公钥只有 N 字节，adb 结构体需要 524 字节` |
| public key declares 64 modulus words | `导入失败：公钥模数长度是 N 字，不是 2048 位（64 字）` |
| the two halves match | `导入失败：私钥与公钥不是一对（adb 公钥结构体对不上）` |

The halves are compared by re-encoding the private key's public half with
`AndroidPubkey.encode` and comparing it byte for byte against the downloaded
blob: the adb encoding is deterministic (both `n0inv` and `rr` derive from the
modulus), so a mismatch means someone else's public key was served and the
phone would reject authentication anyway.

**Reading n/e without a CRT cast (v0.6.4-pad12).** The old code did
`privateKey as? RSAPrivateCrtKey ?: fail("私钥不是 RSA CRT 私钥（拿不到模数）")`,
and the same cast lived in `build()` — so on a platform that returns a plain
`RSAPrivateKey` the app could not even *load* its own key. Measured on the tablet
(Android 12 / API 31): `shkey import` failed with `exit 6` on every attempt while
the very same key imported fine on Android 16. The key was never the problem.

`publicFrom()` now tries three paths, cheapest first: the CRT interface → the
PKCS#8 DER walked by `RsaDer.modulusAndExponent()` → give up. `RsaDer` is a
minimal hand-written DER reader (no third-party library): `PrivateKeyInfo` →
`algorithm` OID must be `1.2.840.113549.1.1.1` → `privateKey` OCTET STRING →
`RSAPrivateKey` → `modulus`, `publicExponent`. It refuses anything it does not
recognise: wrong OID, truncated buffer, indefinite length, modulus under 128
bytes, non-positive or even exponent, `e = 1`. A guess here would produce a
public key that fails the byte-for-byte comparison above, or worse, a handshake
that fails much later with a much less useful message.

Files are written to a temporary name and renamed, so a crash leaves the old
key intact rather than half a new one, and the process-wide `SSLContext` is
cleared afterwards — otherwise the old `KeyPair` stays pinned and the symptom
is "import succeeded but connecting fails". The self-signed certificate is
recomputed from the imported private key for the same reason.

**`shkey show`** is the read-only counterpart: three lines, `来源: 导入|自生成|未知（旧版本留下的密钥）`,
`公钥: …`, `指纹: SHA256:…`. The origin is recorded in `filesDir/adb/adbkey.origin`,
so a key that predates this version is reported honestly as unknown rather than
guessed.

**`shkey adopt` is deliberately not implemented.** The idea was to append this
app's own public key to `/data/misc/adb/adb_keys`, which would have made pairing
unnecessary. Measured on the target device (`ro.build.type=user`): `ls -l
/data/misc/adb/` as `uid 2000` is `Permission denied` — the **directory** is
adbd-private, not just the file, and no shell command can get past it. The verb
therefore exists only to answer with that explanation and point at `shkey
import`, and it exits **6** rather than pretending to have worked.
**What it still cannot do.** No root (`id` reports `uid=2000(shell)`); no
`pm grant` for signature permissions (the shell user cannot hand those out
either); no writing to another app's private storage. It is the same authority
`adb shell` has over USB — nothing more.

### 3.17 `watchdog`: surviving an install, and surviving the app being killed

Flyme never delivers `MY_PACKAGE_REPLACED` (§3.15 measured it), and the app is
not a launcher, so after a silent `pm install -r` nothing on the phone has any
reason to start it. Two independent mechanisms cover that, and they are
deliberately separate because they fail differently.

**Trigger 1 — opening the UI starts the service.** `MainActivity.onCreate` ends
with the same decision the boot receiver makes:

```
StartPolicy.shouldStartOnOpen(configured, autoStart, running)
  = configured && autoStart && !running
```

`autoStart` is a hard gate, not a hint. Pressing 停止 writes `auto_start=false`
(§6), so a phone the operator deliberately silenced cannot be revived by
somebody opening the app. The four rows of that truth table are unit-tested
(`StartPolicyTest`); the failure mode this prevents is the v0.2.9 regression
where "stop" was a suggestion.

**Trigger 2 — a guard script that outlives the app.** Once the agent is running
and self-ADB is connected, `Watchdog.armQuietly()` writes
`/data/local/tmp/phoneagent-guard.sh`, `chmod 755`, and starts it with
`setsid sh … >/dev/null 2>&1 &`. `setsid` detaches it from the app's process
group, and the script runs as `uid 2000`, so `am force-stop net.de5.phoneagent`
does not touch it — measured on the target device: a `setsid`-detached `sleep 25`
survived a force-stop and wrote its file 32 s later.

The script is generated in Kotlin and written through the PTY shell; it is
never downloaded. Its loop:

```
every 60 s:
  if either stop marker exists -> exit
  if `dumpsys activity services net.de5.phoneagent` mentions RcAgentService -> sleep
  else append a timestamp to phoneagent-guard.log, then
       `am start-foreground-service -n …/.RcAgentService`
    || `am start -n …/.MainActivity`   (fallback only)
```

Both `am` verbs work from `uid 2000` (measured: `Starting: Intent { cmp=…/.MainActivity }`,
`topResumedActivity` becomes ours), so the guard needs nobody to touch the screen.

**Service first, UI only as a fallback (v0.6.5-pad12).** `am start` on the activity
necessarily brings the UI to the front, and that is what the owner complained about:
after pressing 停止 the screen popped up again every 60 s. `am
start-foreground-service` revives the service without building any UI, so it is
tried first. It cannot be the only path: from Android 12 on, starting a foreground
service from the background may be refused (`BackgroundServiceStartNotAllowed`), and
a guard that refuses to revive anything is worse than one that occasionally flashes
a page. Hence `|| am start`: the activity runs only when the service start failed.

| File | Purpose |
|---|---|
| `/data/local/tmp/phoneagent-guard.sh` | the loop above, `chmod 755` |
| `/data/local/tmp/phoneagent-guard.log` | one `YYYY-MM-DDTHH:MM:SS restart` line per revival |
| `/data/local/tmp/phoneagent-guard.off` | stop marker written by `disarm` (uid 2000 only) |
| `/sdcard/.phoneagent-stop` | stop marker the **app itself** can write (v0.6.5-pad12) |

**Two stop markers, because one of them needs ADB (v0.6.5-pad12).** The
`/data/local/tmp` marker and `pkill` are both reachable only through self-ADB. On
cellular there is no self-ADB (§ below), so pressing 停止 could write neither: the
service died, the guard survived, and 60 s later it revived the UI. The owner
reported exactly that. `StopFlag` therefore writes `/sdcard/.phoneagent-stop` —
the app holds `MANAGE_EXTERNAL_STORAGE`, so it can write the shared volume's root,
and a shell running as `uid 2000` sits in `sdcard_rw` and can read it. Both markers
are checked twice, once before the loop and once per beat. `StopFlag.set` never
throws: it returns false and logs one line when `/sdcard` is not writable (no all-files
access), because a silent failure here looks exactly like the original bug. `arm`
clears the flag **before** it probes, otherwise a leftover flag makes the fresh script
`exit 0` on its first beat — `arm` would report success and the guard would vanish.

**Four rules the guard has to obey**, each with a test:

- **Never two copies.** `arm` probes `pgrep -f phoneagent-guard.sh` first and
  returns `…未重复启动` with the existing pids instead of starting another. The
  heartbeat re-arms on a 5 min cadence (`Watchdog.ensureArmed`), so without this
  rule a long-running phone would collect a copy per heartbeat.
- **`arm` only when there is something to guard.** `WatchdogPlan.shouldArm(pids,
  scriptCurrent, serviceRunning)` is
  `serviceRunning && (pids.isEmpty() || !scriptCurrent)` — a guard started before
  the service exists would immediately `am start` a UI for no reason.
- **A stale script is worth replacing.** The script carries its version on line 2
  (`# ver=v0.6.3-pad12`); `arm` counts hits with
  `grep -c ver=<version> <path>` and, when the count is 0, kills the old copy
  before writing the new one. Without this, a phone armed once by v0.5.8 kept
  running v0.5.8's script forever: `arm` saw a live pid and answered 未重复启动,
  so a fix to the script could never reach a phone in the field.
- **Stop means stop, through both doors and without ADB.** Pressing 停止 writes the
  app-side marker `/sdcard/.phoneagent-stop` (no ADB needed) *and* goes through
  `disarm`, which writes `/data/local/tmp/…off` and `pkill -f phoneagent-guard.sh`.
  The relay's `stop` command does exactly the same,
  because it routes through the same rule: `StartPolicy.onRemoteStop()` returns the
  identical `Decision(start=false, autoStart=false)` that `onToggle(wasRunning=true)`
  returns, and the service lambda applies it before `stopSelf()`. v0.5.5 got this
  wrong — `Executor.stopAgent()` called `stopSelf()` and left `auto_start` true, so
  `shouldDisarm(true)` declined to disarm, the guard saw a dead service 60 s later
  and ran `am start`, and opening the UI would have revived it too. `onRemoteStop`
  exists so the two stop paths cannot fork again; `StartPolicyTest` asserts the
  remote decision equals the button decision and that `shouldDisarm` accepts it.
  `WatchdogPlan.shouldDisarm(autoStart)` keys off `autoStart`, not off the service
  state: `START_STICKY` restarts the service after a kill while `autoStart` is still
  true, and that restart must not be read as "the operator wants the guard back".
  Without the marker a killed guard would be resurrected by the next heartbeat and
  the service would come back two minutes after the operator silenced it.

**The guard is only as available as self-ADB, which needs Wi-Fi.** Self-ADB talks
to `127.0.0.1`, but the port it dials is discovered through mDNS, and mDNS
multicast does not leave a mobile-data interface. Measured on the target phone
while it was on cellular (`10.201.131.111`): `adbheal` set `adb_wifi_enabled=1`
yet neither `NsdManager` nor a raw mDNS query found `_adb-tls-connect._tcp`, so
`shconnect` cannot succeed and `watchdog arm` degrades to
`守护未启用：自连 ADB 未连接`. On cellular the guard is simply not available; the
UI-open trigger (§ above) is the only self-start path, and it still works because
it needs no ADB at all.

**When self-ADB is not connected, arming is skipped silently** — one `AgentLog`
line, `守护未启用：自连 ADB 未连接`, and no error. A phone whose wireless
debugging the ROM keeps switching off (§3.4) would otherwise log a refusal every
five minutes forever. The heartbeat retries, so the guard appears by itself as
soon as `shconnect` succeeds.

**`watchdog status`** prints seven lines and never fails (it is a report):

```
守护: 运行中（pid 12345）| 未运行 | 未知（自连 ADB 未连接）
最近重启: 2026-11-25T16:20:31 | (无记录)
自连 ADB: 已连接 | 未连接
停止标记: 存在 | 不存在
脚本: /data/local/tmp/phoneagent-guard.sh
间隔: 60s
命令: watchdog [status] | watchdog arm | watchdog disarm
```

`status` also gains one JSON field, `"guard":"running"|"off"|"unknown"`. No
existing field is renamed — a controller that only reads `ver`/`acc`/`bat` keeps
working.

**What it cannot do.** The guard cannot survive a reboot (`/data/local/tmp` is
wiped and nothing restarts it — the boot path is `BootReceiver`, §7), it cannot
survive the ROM clearing `uid 2000` background processes, and it does not touch
`auto_start`: if the operator stopped the agent, the guard exits at its next tick
and stays gone.
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
- `up` is `click`'s second switch, also leading only, and it takes any order with
  `apply`: `click up 删除`, `click apply up 删除`, `click up apply 删除`. It is the
  opt-in for pressing a clickable **ancestor** when the matched node has no rect a
  gesture can use (§5 `z`). Without it that case stays `exit=5`; with it the tap
  happens and the reply names the ancestor. It is deliberately **not** automatic:
  walking up crosses a semantic boundary. A list row is one clickable container and
  the 「删除」 label inside it belongs to a different control than the row — tapping
  the row opens the detail page, it does not delete. The container is the only thing
  with a rect, so reaching it is a guess about intent, and a wrong guess on a phone
  nobody is holding is destructive. `up` is consumed only when a selector follows
  it, so `click up` keeps meaning "the node labelled up"; a selector that *begins*
  with the word stays reachable through a kind prefix (`click text:up arrow`),
  exactly like `apply`. `longclick` has no `up`: a long press has no ancestor
  fallback to opt into, and `longclick up SEL` keeps meaning "the node labelled up".
- Attempt order for `click apply`: `ACTION_CLICK` on the node (or its clickable
  ancestor) → if that returns false, fall back to a gesture tap at the **centre of
  the node's bounds**. Report which path was used. When there is no usable centre
  (the `z` case) the answer is `exit=5` `node has no usable bounds: <SEL>
  matches=<n>`; when a clickable ancestor with a usable rect exists **and that
  ancestor overlaps the node's rect**, the same line continues `; nearest clickable
  ancestor is <class> "<text-or-desc>" at <l,t,r,b> — add \`up\` to tap it`, and with
  `up` that ancestor's centre is tapped instead. No ancestor at all keeps the line
  exactly as it was.
- The ancestor must **overlap** the node on both axes (v0.8.5-pad12). The two rects
  are normalised to proper boxes first (an inverted `z` rect still says *where* the
  node is), and they count as overlapping when `aLeft <= bRight && bLeft <= aRight &&
  aTop <= bBottom && bTop <= aBottom` — inclusive, so a zero-width or zero-height node
  that only touches its container's edge still belongs to it. A missing rect on either
  side is not an overlap.
- Why the walk needs geometry at all: walking up alone is not enough on a real page.
  Measured on a 糖豆 page (1200x2670), **all 113** text nodes with a `z` rect reached
  the same off-screen 注册 `Button` at `1060,402,1153,451` — the WebView had pushed it
  onto the same line — and 25 of them would have been tapped there, on the wrong
  control entirely. A parent normally contains its child, so the ancestor that really
  owns the label always shares pixels with it; the cross-semantic one does not. With
  the rule the count is 27 of 113, and every one of the 27 lands on the full-width
  player bar `0,2536,1201,2590` that really does hold the label. The rule is
  deliberately looser than "fully contains": a label that spills a few pixels outside
  its container is still that container's label, and the stricter test would lose real
  taps for no safety gain (measured: the same 27 either way on that page).
- Attempt order for `longclick apply`: `ACTION_LONG_CLICK` → gesture long press at
  the centre (default 600 ms).
- `stdout` on success: `clicked via=action|ancestor|gesture node=<resId|text> at=<x>,<y>`
  (the label is `resId` if present, otherwise the first 40 chars of `text`, then
  `desc`). `longclick` prints `longclicked …` with the same shape. The `up` path
  keeps `node=`/`at=` — `at=` is where the tap landed, which is the ancestor's
  centre — and appends `ancestor=<class> <l,t,r,b>`. `up` with no ancestor is
  `exit=5`, `no clickable ancestor for: <class> "<text-or-desc>" at <l,t,r,b>`.
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

### 3.18 `logs size` / `logs trim`: three ledgers, one question

The agent writes logs in three unrelated places, and before v0.5.8-pad12 nobody
could answer "how big is it" without a shell. `logs size` reports all three; the
same numbers appear in the debug page summary (§6.1) so the operator and the
owner are looking at one truth.

| Ledger | Where | Cap | Who trims |
|---|---|---|---|
| in-memory ring | `AgentLog` | `AgentLog.MAX_LINES` = 400 lines | `AgentLog.add` drops the oldest and counts it (`droppedCount`) |
| guard log | `/data/local/tmp/phoneagent-guard.log`, written by the watchdog script (§3.17) | `WatchdogPlan.LOG_MAX_BYTES` = 64 KB | the script itself: before each 60 s beat, if the file is over the cap it keeps the last `LOG_KEEP_LINES` = 200 lines; `Watchdog.arm` also trims once at arm time |
| cached APKs | `cacheDir` | `LogSizes.APK_STALE_MS` = 24 h | `Downloader.pruneNow` at service start, plus `logs trim apply` |

**Why the script trims itself.** The guard log is written by a shell loop that
outlives the app (that is the whole point of §3.17), so nothing in-process can
keep it bounded. `tail -n 200` costs nothing on a 64 KB file and the loop is
already awake once a minute; a log that grows until the phone is full is a bug
that only shows up months later on somebody else's phone.

**Why a dry run is the default.** `logs trim` with no argument deletes nothing —
it prints what it *would* remove and ends with
`dry-run: 没有删除任何东西，加 apply 才真删。`. Trimming is irreversible and the
relay carries the operator's keystrokes, not their eyes; `apply` has to be typed
on purpose.

**Reading the guard log is a self-ADB round trip.** `GuardProbe` shells out over
our own adbd (§3.16) with a 10 s timeout, so:

- a missing file is reported as 文件还没建，守护脚本还没 arm 过 — not as 0 B, because
  "empty" and "absent" are different problems;
- a timeout is reported as 读取超时（10000ms 内没有结果） and the byte count stays
  unknown rather than being filled in with a zero;
- `apply` that cannot read the result back says 已发出裁剪命令，但没回读到结果
  instead of claiming success;
- `parseGuard` takes the **last** `B=`/`L=` pair in the reply, because the PTY
  echoes the command line itself (where the numbers are still `$(wc ...)`), and a
  reply carrying only one of the two numbers is treated as unreadable rather than
  half-trusted.

The debug page measures this ledger on its own 30 s timer
(`DebugActivity.GUARD_REFRESH_MS`), not on the 2 s summary refresh: one round
trip per two seconds would keep the shell busy for no new information.

### 3.19 `logs` on Android 16: read it as uid 2000, never as ourselves

Android 16 added a **runtime** "log access" grant on top of the `READ_LOGS`
permission. When the app starts `logcat` with its own uid, the system puts up
`com.android.systemui.logcat.LogAccessDialogActivity` — 「允许 Phone Agent 访问所有
设备日志吗？」 with 允许访问一次 / 不允许 — and it comes back **every time**, because
the only answer on offer is "once". On a phone nobody is holding, that dialog is
never answered, so the app-read path returns nothing at all.

`pm grant android.permission.READ_LOGS` does **not** fix it: `dumpsys` then shows
`granted=true` and the dialog still appears. The permission and the runtime grant
are two different questions, and only one of them is answerable from adb.

uid 2000 is not asked at all. `sh apply logcat -d -t 5` over our own adbd (§3.16)
returns output with no dialog, so `logs` now tries that first:

```
LogSource.plan(SelfAdb.isConnected(), lines)
  connected    -> Plan.Adb("logcat -d -t N")        // SelfAdb.shell, 30 s budget
  not connected -> Plan.Local(["logcat","-d","-t",N]) // ProcessBuilder, as before
```

Both paths clamp to `Commands.LOGS_LINES_MAX` through the same `LogSource.clamp`,
so the two branches can never disagree about how many lines were asked for. The
decision itself is a pure function and is pinned in `LogSourceTest`.

When the fallback returns nothing, the message has to redirect rather than
describe. It says the dialog exists, that it re-appears every time so it cannot
be answered remotely, that `pm grant` is not the fix, and then names the way out:

> logcat 无输出。Android 16 起 App 直接读日志要弹系统授权框（「允许 Phone Agent 访问
> 所有设备日志吗？」，只给「允许一次」，每次都要重弹），远程点不了，所以这条路在
> 无人值守时不可用；pm grant READ_LOGS 也没用（权限显示已授予，弹框照旧）。建议先
> shconnect 连上自连 ADB 再执行 logs，或直接 sh apply logcat -d -t N

Exit codes: a disconnected self-ADB link is `exit=6` (permission/precondition,
same as `sh`), a shell that never answers is `exit=4`, and a non-zero remote exit
still returns `exit=0` with the text — the command ran, it just had nothing
useful to say, and the operator can read the remote `exit=N` in the body.

### 3.20 `mp3`: video soundtracks to MP3, on the phone

The owner asked for one thing: drop videos into a folder, press one button, get
mp3 files. No PC, no ffmpeg download, no online service.

```
mp3 scan [DIR]               # read-only inventory
mp3 run [apply] [force] [DIR]
```

`DIR` defaults to `/sdcard/视频转音乐专用` (`Mp3Plan.DEFAULT_DIR`); the
recognised suffixes are `mp4 mov mkv avi flv m4v 3gp webm ts mpg`. Output is
`128 kbps / 44100 Hz`, stereo for stereo sources, mono kept mono, written as
`<same name>.mp3` **next to** the source. A source whose mp3 already exists is
skipped unless `force` is given. A file with no audio track is skipped and
counted separately — "no audio" and "already done" are different answers, and
only one of them means the owner has something to listen to.

**Why not ffmpeg.** Two doors are both shut. Since targetSdk 34 the app may not
`exec` a binary it wrote into its own private directory, which kills the
`ffmpeg -i … -vn -b:a 128k` line that the tablet ran at 52× realtime; and
shipping ffmpeg in the APK adds ~20 MB to a 2.7 MB app. So decoding is
`MediaExtractor` + `MediaCodec` (platform, zero bytes) and encoding is
**jump3r**, the pure-Java LAME port (§ third-party notices): 71 vendored
`.java` files, ~350 KB in the APK, no `javax.sound`, no JNI.

```
scan:   Mp3Converter.scan(dir)
          File.listFiles + Mp3Plan.isVideoName
          per file: MediaExtractor → first audio/ track? KEY_DURATION
          targetExists = <name>.mp3 isFile
        → Mp3Plan.scanReport(dir, rows)

run:    scan → Mp3Plan.dryRunLine(rows, force)          // no apply
        scan → Mp3Runner.run(dir, rows, force)          // apply
          per file: MediaCodec decode → LameMp3Encoder → <name>.mp3.part
                    rename .part → .mp3 only on success
```

**Sample scale, and the bug it caused.** `lame_encode_buffer_int` does *not*
take 16-bit samples. It multiplies every value by `1.0f / (1L << 16)` before the
encoder sees it (`app/src/main/java/de/sciss/jump3r/mp3/Lame.java:1935-1936`,
comment: "internal code expects +/- 32768.0"), so the value it wants is a 16-bit
sample **shifted left by 16** — full scale is ±2^31, not ±32768.
`LameMp3Encoder.toLameInt` does that shift, once, on every sample it is handed.
v0.7.0-pad12 shipped without it: the MP3s were structurally perfect (right
duration, right bitrate, valid frame headers) and measured
`mean_volume -91.0 dB / max_volume -90.3 dB` — silence, at 1/65536 of full
scale. The owner read that as "it won't play". The scale is now pinned by
`LameMp3EncoderTest.theSampleScaleMatchesWhatLameActuallyExpects`, and the
whole path was verified end to end with `ffmpeg -af volumedetect`: the same
full-scale sine gives `mean_volume -4.3 dB / max_volume 0.0 dB` after the fix
and `-91.0 / -90.3 dB` when the shift is removed.

**Decode shape comes from the decoder, not the track header** (v0.7.2-pad12).
Three numbers decide how a block of PCM is read — sample rate, channel count, and
whether the samples are 16-bit ints or floats — and the authoritative source for
all three is `MediaCodec.outputFormat`, not the `MediaExtractor` track format.
`DecodeShape.decodeShapeOf` takes both sides (each field nullable, `null` meaning
"this side did not declare it") and settles them:

| field | rule |
|---|---|
| rate | `outRate` if > 0, else `trackRate` if > 0, else 44100 |
| channels | `outChannels` if 1 or 2, else `trackChannels` if 1 or 2, else 2 |
| isFloat | `outPcmEncoding == ENCODING_PCM_FLOAT`; if the output is silent about it, the track's word; else **false** (short is the platform default, not a guess) |

Two real failures came out of getting this wrong, and both hit ordinary phone
videos rather than exotic ones. A track that declares float while the decoder
emits short (androidx/media issue #565 — pcm-encoding 4 reported as 2) used to be
read as float, i.e. as noise. And an HE-AAC track whose header says 22050 while
the decoder's SBR doubles it to 44100 used to trip the "解码器改变了格式" check
and fail a perfectly good video — the encoder had already been built from the
track header by then.

So the encoder is now **created lazily**, at the first block of PCM, once
`outputFormat` can be read. A later `INFO_OUTPUT_FORMAT_CHANGED` only updates how
the bytes are *read* (short ↔ float, safe); if the rate or the channel count
actually moves mid-stream it throws a sentence that says so, because re-tuning a
running LAME instance is not something this code does.

**Surround sound is refused, not downmixed.** If the decoder reports more than two
channels, `decodeShapeOf` returns a non-null `unsupported` sentence and
`convert()` throws it. Feeding 6-channel interleaved samples to a 2-channel
encoder shifts every sample and produces screech — worse than failing, because
the owner cannot tell that kind of broken from a broken file. A 6-channel *track*
that the decoder folds down to 2 is fine: the decoder's word wins. The sentence
travels back as `unsupported` rather than as an exception so the rule stays
testable without try/catch around every assertion.

`ConvertResult.channels` / `.inRate` report the shape the encoder actually used,
not what the track header claimed — the two differ exactly on the HE-AAC files
where someone would be reading those numbers to debug.

The batch loop lives in `Mp3Runner` rather than in `CommandExecutor` because it
has **two doors**: `mp3 run apply` over the relay and the 开始转换 button on
`ConvertActivity` (§6.2). Two copies of "who gets skipped, what `force` means, how a
failure is counted" would drift apart, and then the inventory the owner sees and
the work the app does would disagree.

Each file gets its own **30-minute** budget (`Mp3Runner.FILE_TIMEOUT_MS`). A
decoder that wedges on a corrupt container would otherwise stop the whole batch,
and one bad file must not cost the other twenty. The number comes from the
measured 7× realtime on the tablet (Lenovo TB-J607F: 2:30 of video in 21 s), so
30 minutes covers a 3.5-hour recording — a full concert, which is the worst case
the owner is realistically going to drop in.

**A timeout does not stop the conversion.** The worker is a daemon thread that
the batch stops *waiting* on, not one it can kill: minutes later it may still
finish and rename its `.part` to a real `.mp3`. The batch has already reported
失败 for that file, and the next `mp3 scan` will list it as 已有 mp3. That is
benign — the file is a good one — but it reads like a ghost if you do not know
it, so the failure line says 转换超时（超过 30 分钟，文件可能还在后台写） and this
paragraph exists. Do not "fix" it by deleting the late file.

The `.part` rename matters: a conversion killed halfway must not leave a
half-length mp3 that the *next* run then treats as "already done" and skips
forever.

`mp3 scan` refuses `apply`/`force` with exit 2 rather than ignoring them —
a silently dropped `apply` reads as "it ran".

Exit codes: a missing/unreadable directory is `exit=2` (the path is the
argument, and it is wrong); a scan that never returns is `exit=4`; **a batch
with failures is still `exit=0`**, because the command itself ran — which files
converted is in the body, and the summary line (`完成 N 个，失败 M 个，跳过 K 个，
无音轨 J 个，用时 T`) is the honest place to read it.

Body shape (`mp3 run apply`, one line per event, so the phone UI can stream it):

```
[1/3] 生日.mp4 …
[1/3] 生日.mp4 40%
[1/3] 生日.mp4 → 生日.mp3 4.2 MB，用时 3.5s
[2/3] 坏文件.mkv 失败: 没有音轨
完成 2 个，失败 1 个，跳过 0 个，无音轨 0 个，用时 7.5s
```

Measured on this laptop's JVM (`LameMp3EncoderTest`): encoding alone runs at
**45× realtime** (10 s of audio in 221 ms; repeated runs land between 43× and
45×), so the encoder is not the bottleneck — `MediaCodec` is.

### 3.21 `where`: which screen am I on, in one round trip

```
where
```

One line of JSON, nothing else:

```
{"ok":true,"pkg":"com.tencent.mm","act":"com.tencent.mm.ui.chatting.variants.ChattingMainUI","scr":"on","kg":"none"}
```

`pkg` is read from the accessibility service (`rootInActiveWindow`), so it needs
no shell and no extra permission — but it does need the service: without it the
command is `exit=3`, the same code every other UI command uses. `act` is
best-effort and may be an empty string: it comes from
`dumpsys window | grep mCurrentFocus` as uid 2000, so self-ADB being down costs
the activity name, not the command. The real dump line is usually followed by
`Failed to write while dumping service window: Broken pipe` (exit is still 0),
so the parser takes the class after the last `/` up to the closing brace or the
first space and ignores everything else. `scr` is `on`/`off`, `kg` is
`none`/`locked`/`secure` — the same three words `wake` reports.

Why it exists: the loop used to be `tree` → read a screenful → guess → `tree`
again. Asking "which page is in front?" first is one short line and usually
removes one of the two trees.

Exit codes: 0 answered, 2 usage, 3 accessibility service not connected.

### 3.22 `media`: five transport keys, two channels

```
media play|pause|next|prev|stop
```

No `apply`: a transport key is not a sticky setting, and the owner pressing
next once cannot break anything. Keycodes are the platform ones — play 126,
pause 127, next 87, prev 88, stop 86 — sent as `input keyevent <code>` over
self-ADB (`next` is the one verified on the phone: it advanced the player).
Without self-ADB the command falls back to
`AudioManager.dispatchMediaKeyEvent` from our own process, which works for some
players and is silently ignored by others, so the receipt says
`media next via=audiomanager（未连自连 ADB，这条通道不保证生效；要稳就用 shconnect）`
rather than claiming success. A remote failure is `exit=6` and names the code
and the device's own text.

`media` is deliberately **not** part of `key` (`Commands.KEY_NAMES` still holds
exactly back / home / recents / notifications / quicksettings): `key play`
stays a usage error, so nobody can change what `key` means by accident.

Exit codes: 0 sent, 2 usage, 6 permission (self-ADB answered non-zero), 4 timeout.

### 3.23 `status` grows a twelfth key: `shell`

`shell` is the **twelfth** key (v0.8.0 added it, appended last). The eleven keys
that were already there — v0.5.5's ten plus `guard` — keep their names and their
order, one for one. It is `ok`/`down` from `SelfAdb.isConnected()`:

```
{"ok":true,"ver":"0.8.1-pad12","acc":true,"svc":true,"scr":true,"bat":88,"loc":true,"loop":60,"opt":"on","queue":0,"guard":"idle","shell":"ok"}
```

Nothing is renamed: the controller reads this object by field name, and the
answer to "why did `wm` say shconnect?" belongs in the one line a controller
already polls every few minutes.

### 3.24 `battery`: the optimisation whitelist, read and written

```
battery                       # read: battery whitelisted | battery not-whitelisted
battery allow [apply]         # cmd deviceidle whitelist +net.de5.phoneagent
battery deny  [apply]         # cmd deviceidle whitelist -net.de5.phoneagent
```

The read half uses `PowerManager.isIgnoringBatteryOptimizations`, i.e. the same
fact the config page's lamp shows, and needs no shell at all. The write half is
`cmd deviceidle whitelist` as uid 2000 — the app cannot whitelist itself through
the public API without a system dialog — so without self-ADB it is `exit=6`
with a sentence that says `shconnect` instead of just failing.

`apply` is required to change anything; without it the receipt is
`dry-run: would run sh: cmd deviceidle whitelist +net.de5.phoneagent`.

**The receipt distinguishes three outcomes** (v0.8.1). The state is read before the
command runs, so the answer says whether anything actually changed:

| before | `battery allow apply` | `battery deny apply` |
|---|---|---|
| not whitelisted | `battery 已加入电池优化白名单` | `battery 本来就不在白名单里（未改动）` |
| whitelisted | `battery 本来就在白名单里（未改动）` | `battery 已移出电池优化白名单` |
| unreadable | the two "已…" sentences (the v0.8.0 wording) | same |

Before v0.8.1 a phone that was already whitelisted still answered "已加入", which
told the operator nothing about whether the command had done any work. If the
state cannot be read the command does **not** refuse to run — it falls back to the
old wording rather than blocking a real change on a failed read.

Exit codes: 0 read or written (and 0 for the dry run), 2 usage, 6 permission,
4 timeout.

### 3.25 `notify post`: put a sentence on the phone's screen

```
notify post apply TITLE BODY     # apply in front
notify post TITLE BODY apply     # apply at the end — same thing (v0.8.1)
```

`apply` is accepted on **either side**, and the two forms produce one identical
command. v0.8.0 only took the front position while `wm size 900x1600 apply` puts
it at the end; the same app with two rules got exactly the accident it invited —
`notify post 测试标题 测试正文 apply` treated `apply` as part of the body and
dry-ran a notification reading "测试正文 apply". The rules now:

- front `apply` is stripped first; anything after it, including a trailing
  `apply`, stays in the body (`post apply T A apply` → body `A apply`);
- a trailing `apply` is stripped only when a title **and** a body remain, so
  `post T apply` keeps `apply` as its body and `post T 请 apply 一下` keeps the
  sentence intact;
- the one body the trailing form cannot express is one whose **last word** is
  `apply`: `post T 请 apply` is read as the switch (body `请`), because a stray
  switch is far more common than a body ending in that English word. Write the
  switch in front instead and the word is left alone —
  `post apply T 请 apply` posts the body `请 apply`;
- the dry run names the command it would really send, so a body that still
  carries `apply` is visible before anything is posted.

`cmd notification post -S bigtext -t "TITLE" phoneagent "BODY"`, as uid 2000.
Default is a dry run; `apply` posts it. The title is one token, the body is
everything after it, **spacing preserved** (it is text a human will read), capped
at 80 and 200 characters — over the cap is a usage error rather than a silent
truncation. Quotes, `$` and backticks in either field are escaped for the shell.

The first word is `notify`, not `notif`: `notif` is the existing "list the
active notifications" command and the two must not be confused.

Android 12's `cmd notification` does not always accept `-S bigtext`. When the
first attempt fails the command drops that flag and tries once more; if the retry
works the receipt says so (`notify 已发送（这条 ROM 不认 -S bigtext，已去掉后重试成功）`)
instead of pretending the first form was fine.

Exit codes: 0 posted (or dry run), 2 usage, 6 permission, 4 timeout.

### 3.26 `wm`: display size and density, with a fuse

```
wm                                                  # read
wm size 900x1600 [SECONDS] apply                    # wm size 900x1600
wm size reset apply                                 # wm size reset
wm density 400 [SECONDS] apply                      # wm density 400
wm density reset apply                              # wm density reset
```

Read is one line, fixed order: `wm size 1200x2670 density 520`. Everything goes
through uid 2000; without self-ADB it is `exit=6` naming `shconnect`.

**Read reports what is in force, not what the panel was built as** (v0.8.2). Once a
size or density is overridden, `wm size` prints two lines — `Physical size:
1200x2670` and `Override size: 900x1600` — and the device is really using the
override. v0.8.1 read the physical line for both purposes, so a phone that had
just been set to 900x1600 answered `wm size 1200x2670 density 520` and looked like
the change had failed. Two different questions now have two different answers:

| question | helper | prefers |
|---|---|---|
| what is in force right now? (the read command) | `currentSize` / `currentDensity` | `Override`, then `Physical` |
| what will `reset` return to? (the fuse baseline) | `originalSize` / `originalDensity` | `Physical`, then `Override` |

With nothing overridden the line is **byte for byte** the old
`wm size 1200x2670 density 520`, so existing parsers keep working. With an
override in force it carries the physical values as an annotation:
`wm size 900x1600 density 520 (override; physical 1200x2670/520)` — the current
value leads, the baseline is still on the line, and a value that could not be read
is `?` rather than an invention.

**Every command sent to the phone starts with `wm `** (v0.8.1). v0.8.0 built the
apply command as `kind + " " + value` and dropped the prefix, so on a real
MEIZU_22 / Android 16 `wm size 900x1600 apply` executed `size 900x1600` and
answered `exit=6 错误: wm 执行失败（size 900x1600 返回 127）：/system/bin/sh: size:
inaccessible or not found`. Read and both resets were correct because their
constants carry the prefix — which is why the unit tests passed. `WmPlanTest`
now asserts that the read constants, the reset constants **and** every parsed
apply command all start with `wm `, so a fragment can never ship again.

**The fuse is the point.** A resolution the phone cannot drive leaves the UI
unusable — no status bar, buttons off-screen — and the usual way out is a factory
reset. So before a real change the app stores the original values and a deadline
in `Prefs` (`wm_deadline`, `wm_original`), default 300 s, settable from 30 to
3600 s with a third token. `CommandExecutor.execute` calls `WmExpiry.check`
before **every** command: past the deadline it runs `wm size reset` and
`wm density reset`, and the next receipt carries `已自动恢复显示设置（超时）`.
If the reset cannot be delivered (self-ADB dropped) the fuse is deliberately
**not** disarmed — the next command tries again. Better a few extra attempts than
a phone stuck in a broken resolution that nobody is watching.

The "original" value is read from the `Physical` line, never the `Override`
one: after a change `wm size` prints both, and the override is the temporary
value we just set — recording that as the baseline would make the note lie. This
is `originalSize` / `originalDensity`, and it stays on `Physical` even while the
read command reports the override: the two helpers are deliberately separate so
that fixing one cannot quietly break the other.

`wm size reset apply` and `wm density reset apply` clear the fuse (there is
nothing left to restore). `reset` takes no SECONDS: it is immediate.

Exit codes: 0 read or written (and 0 for the dry run), 2 usage, 6 permission,
4 timeout.

### 3.27 `autowake`: wake before touching the screen, or refuse

```
autowake            # report: autowake on | autowake off
autowake on|off     # persist (Prefs.KEY_AUTO_WAKE, default off)
```

When it is on, `tap` / `long` / `swipe` / `click` / `longclick` / `tree` /
`shot` / `where` run `Autowake.preflight` first:

| autowake | screen | keyguard | what happens |
|---|---|---|---|
| off | any | any | nothing at all — the old behaviour, byte for byte |
| on | on | any non-secure | nothing to do |
| on | off | none / locked | `Wake.wake`, then the command runs; the receipt gains `wake=via=wakelock` |
| on | any | secure | `exit=6` `锁屏有密码，界面操作不可用；请先手动解锁` — the command does **not** run |

The refusal is the whole point. Running anyway on a password-locked phone produces
`no node found` — which reads as "your selector is wrong" and sends the operator
hunting for a bug that is not there.

**On a password-locked phone `autowake` will never light the screen, and that is
correct** (recorded v0.8.2 after a real-device question). `AutowakePlan.decide`
tests `keyguard == secure` **before** `screenOn`, and a locked phone reports
`secure` from `KeyguardManager` whether the screen is on or off — real device:
`wake ok via=wakelock scr=on kg=secure`. So on such a device the only thing
`autowake` buys is turning a misleading failure into a stated one: `tap 600 1300`
on a dark password-locked phone answers `exit=6 锁屏有密码，界面操作不可用；请先手动
解锁` instead of the old `没有出现含…的节点`. Do not read "the screen stayed dark"
as a broken feature — the refusal is the feature. On a phone with **no** lock
password the wakelock path does run and the receipt gains `wake=via=wakelock`.

The note is appended, never glued onto JSON: a JSON receipt (`where`, `status`,
`shot`) gets the note on **stderr** (`FeatureNotes.attach`), because one trailing
sentence turns a parseable object into garbage.

Exit codes: 0 reported or set; 2 usage; 6 for a refusal and for a wake that the
ROM refused (`wake failed via=none scr=off kg=…`).

### 3.28 The module foundation: feature 13 costs one file and one line

The first seven features are also the proof that the eighth is cheap. Every
feature lives in its own `Feat<Name>.kt` holding the verb, the parser, the
executor and all of its user-visible text, and implements:

```kotlin
internal interface FeatureModule {
    val verb: String                                  // claims the first word, lowercase
    fun parse(rest: String): Parsed?                  // null = "not mine"
    fun execute(ctx: FeatureContext, args: String): ExecutionResult
}

internal sealed class Parsed {
    class Ok(val rest: String)                        // normalised arguments
    class Bad(val detail: String)                     // a "usage: …" line
}

internal interface FeatureContext {
    val context: Context
    val applyAllowed: Boolean
    fun <T> onMain(timeoutMs: Long, block: () -> T): T?
    fun runShell(command: String, timeoutMs: Long): ShellOut?   // null = no self-ADB
}
```

`FeatureRegistry.modules` is one module per line; `Commands.parse`'s final
`else` branch is `FeatureRegistry.parse(head, rest) ?: Command.Unknown(head)`, and
`CommandExecutor.dispatch` has one `is Command.Module -> FeatureRegistry.execute(...)`
branch. Adding feature 13 is therefore: **one new file + one line in the
registry**. `Commands.kt` and `Executor.kt` are not touched at all, and a test
asserts the verbs are unique and lowercase so a collision fails the build rather
than silently shadowing a command.

A parse failure is always `exit=2` with a `usage: …` line in `stderr`, the same
shape as every hand-written command in this file.

### 3.29 `look`: the camera, not a screenshot

Every other way of seeing the screen in this app goes through the accessibility
tree or `screencap`, and neither returns a photograph. A camera preview is
composited by the camera HAL into a hardware overlay, and whether that overlay
reaches the framebuffer is up to the device: on the 魅族 (1200x2670, Android 16) it
does — a `screencap` of a camera page comes back as a valid 647x1440 PNG with the
preview in it — while on other phones and ROMs the same call returns the black
rectangle where the overlay would be. So a screenshot-based 看图 would work on
this phone and silently fail on the next one, and even when it works the answer
is wrong for the job: a screenshot is the whole page (preview, status line, two
buttons, and the keyboard while it is up) at half the screen's resolution,
letterboxed to the preview's aspect — a picture of a screen, not of the thing the
person pointed at. The only way to get the sensor's own image, at a size we
choose, the right way up, is to open the camera yourself.

`CameraActivity` does exactly that, in one page with four things on it: the camera
switch in the top-right corner (§6.3 item 5), the live preview (a `TextureView`
inside a weight-1 `FrameLayout`), a multi-line `EditText` (`maxLength`
= `CamPlan.MAX_TEXT` = 500 code points, hint 说一句：哪里不对 / 要我看什么) and one
button 发送. No command opens this page — the relay cannot summon it and
nothing here is reachable through `Commands.parse`; it is a page the person
holding the phone opens, because the question — 哪里不对 — is theirs to ask.

**Capture.** Camera2, back camera, `TEMPLATE_PREVIEW` repeating request, and the
JPEG target in the *same* `CaptureSession` as the preview surface:

```kotlin
device.createCaptureSession(listOf(previewSurface, jpegReader.surface), cb, camHandler)
```

Two targets in one session is the whole point: a still capture is a request on
that session which adds the JPEG target, and the frame it delivers is the frame
that was being composed at that instant. A second session for stills would have
to reconfigure the pipeline and would hand back a frame from *after* the preview
stopped — not what the person was looking at.

Sizes come from `SCALER_STREAM_CONFIGURATION_MAP`, never from the largest entry.
`CamPlan.pickSize` scores every offered size as
`|aspect difference| * 4 + |long-side difference| / target long side` and takes
the smallest, so the preview lands near 1280x720 and the JPEG near 1280x720
instead of the sensor's full resolution (several thousand pixels a side on every
phone this app has run on). The two are asked for separately —
`getOutputSizes(SurfaceTexture::class.java)` for the preview,
`getOutputSizes(ImageFormat.JPEG)` for the still — and the reader is
`ImageReader.newInstance(w, h, ImageFormat.JPEG, 2)`. `maxImages = 2` because one
frame can still be in the encoder when the next arrives, and `1` drops frames
under exactly the load this page creates. The reader hands back JPEG bytes
directly (`image.planes[0].buffer`), so nothing in this path ever sees a
`YUV_420_888` plane or a colour conversion.

**Send.** 发送 does, in order:

1. `CamPlan.clampText` — trim, then cut at 500 code points on a code-point
   boundary. Empty → 先打一句话, and nothing is captured at all.
2. Wait `CamPlan.SETTLE_MS` = **300 ms**. The keyboard is on screen while the
   person types; tapping 发送 dismisses it and the window resizes. Capturing
   immediately gives a preview that is still re-laying-out — a black or stretched
   frame at the old aspect — and that frame is what the far end would be asked to
   judge. 300 ms is one layout pass plus a frame or two on this device, short
   enough that a hand holding the phone still does not drift out of frame.
3. `TEMPLATE_STILL_CAPTURE` with the JPEG target and autofocus triggered. The
   bytes are read on the camera thread and handed to a plain `Thread`: decode,
   re-encode and two HTTP calls take seconds, and the preview has to keep running
   while they do.
4. **Re-compress at 1280 px / quality 80.** A full-sensor JPEG is several
   megabytes — a slow upload on mobile data, and far more resolution than "where
   is the problem" needs. `CamPlan.fitScale` never enlarges and returns `1.0f` for
   illegal sizes, so a device that already offers 1280 is left untouched. The
   re-encode also **uprights the pixels** (`Matrix.postRotate` by
   `(sensorOrientation - displayRotation + 360) % 360`): `BitmapFactory` does not
   read EXIF, so the orientation tag is gone the moment the image is decoded, and
   a portrait phone would otherwise send a landscape photo.
5. `RcClient.uploadBlob(name, bytes)` → `PUT {relay}/agent/blob/<name>`,
   `X-RC-Secret`, raw bytes, exactly as §4 describes.
6. `RcClient.postNote(json)` → `POST {relay}/agent/note`.

`name` is `cam-<millis>-<4 hex>.jpg` (`CamPlan.blobName`); a suffix that is not
four `[0-9a-z]` falls back to `0000` rather than producing a name the relay would
reject.

**The note.** One line of JSON, hand-written by `CamPlan.noteBody` so the field
order is fixed and the escaping is testable on a plain JVM:

```
POST /agent/note
  header X-RC-Secret, Content-Type: application/json
  {"id":"PHONE-MEIZU","kind":"look","text":"屏幕中间有一块红的",
   "blob":"cam-1733456789012-a1b2.jpg","w":1280,"h":720}
  -> {"ok":true,"seq":42}
```

| Field | Meaning |
|---|---|
| `id` | the agent id, same regex as §2 |
| `kind` | `look` — a person asking to be looked at, never a `shot` or a `#rc:` action |
| `text` | what the person typed, already trimmed and clamped to 500 code points |
| `blob` | the name uploaded in step 5, fetched back with `GET /ctl/blob/<name>` |
| `w`, `h` | the JPEG's real pixel size **after** the re-compress, not the size requested, so the reader never has to guess whether the image was scaled |
| `seq` | the relay's running counter (in the answer, unused for now) |

**A send with no picture (v0.11.4-pad12).** When the camera switch is off, 发送 skips
steps 2-5 above entirely and posts a note with no blob at all:

```
POST /agent/note
  {"id":"PHONE-MEIZU","kind":"look","text":"屏幕上那个弹窗又出来了",
   "blob":"","w":0,"h":0}
  -> {"ok":true,"seq":43}
```

Same `kind`, same six fields, same endpoint — `blob` is the empty string and `w`/`h`
are `0`. Nothing on the relay side had to change: `PUT /agent/blob/` is simply never
called, and `/agent/note` already accepted a note whose blob is empty (it validates
the name only when there is one). The reader tells the two apart by `blob` alone,
which is why the empty string beats omitting the field — one shape for every note,
so no consumer needs a null check for a key that may or may not be present.
`CamPlan.noteBody` still writes all six fields, which is what
`aTextOnlyNoteHasAnEmptyBlobAndNoSize` pins.

The status line says so in words, through `CamPlan.textOnlyLine`:
已发送：文字 8 字（没带图）, and 已发送：文字（没带图） when the count is not usable.
A person who switched the camera off must never be left wondering whether a photo
went out with the sentence.

`/agent/note` lives on the relay next to `/agent/blob/` and `/agent/result`
(`scripts/rc-relay.py`, added the same day as this page). It validates `id`
against the §2 regex and `blob` against the same name rule as `PUT /agent/blob/`
(400 on either, so a bad name fails at the caller instead of at fetch time), caps
`text` at 2000 characters and `kind` at 24, keeps the last 200 notes per device,
assigns a global monotonic `seq`, and wakes the controller's long poll on
`GET /ctl/notes?since=<seq>` — the counter is global and persisted, so a relay
restart never re-issues a `seq` the controller has already passed. A send whose
note is refused shows 发送失败: <reason> while the blob from step 5 is already
on disk.

**The receipt (v0.11.5-pad12).** 已发送 was never the whole truth: it proved the
phone reached the relay, not that the operator ever pulled the note down. So
`POST /agent/note` answers with a `seq`, and `POST /agent/note-status`
(`{"id":…,"seq":N}`, same `X-RC-Secret` header) answers
`{"ok":true,"seq":N,"read":true|false,"read_at":N}` — or 404
`{"ok":false,"error":"no such note"}` for a seq the relay never stored.
`RcClient.noteStatus` returns `NoteStatus(read, readAt)` and `null` for **any**
failed question (transport error, 404, a body without `ok`): "could not ask" is
not "nobody looked", so the caller retries instead of printing it.

The page says 已上传…等我确认 the moment the note is stored
(`CamPlan.uploadedLine` / `uploadedTextOnlyLine`) and only then starts the receipt
thread, so the next send never waits on one — the box is emptied and 发送 comes
back before the first question is asked. That thread (`Thread(…, "note-ack")`,
`CameraActivity.awaitRead`) asks every `ACK_INTERVAL_MS` = 2 s, `ACK_ATTEMPTS` =
15 times (30 s): `read:true` puts `CamPlan.readLine` (已读) on the line, the end of
the window puts `unreadLine` (服务器收到了，但还没人看), and a window with no
successful answer at all additionally logs `回执查询失败: seq N` — the phone never
claims "unread" for a question it never got answered. One slot
(`@Volatile ackSeq`) holds the newest seq: an older thread compares it before every
write and returns, and `onDestroy` sets it to -1 and interrupts the sleep, so no ack
thread outlives the page.
**Independent of the service.** The page builds its own
`RcClient(prefs.relayBase, prefs.secret, prefs.agentId, agentInfo())` and calls it
directly. `RcClient` is stateless — one call, one connection, no `hello` first, no
dependency on `RcAgentService` being alive — so 摄像头看图 works with the agent
stopped. That is deliberate: the camera is a page for the human, and needing the
service to be running before you can ask a question about the screen would make
it useless in precisely the case where the service is what is broken.

Status is one line above the buttons: 对准要我看的东西，打一句话，按发送, then
正在拍…, then 已上传：图 148 KB（1280x720）+ 文字 12 字，等我确认, then either
已读 or 服务器收到了，但还没人看 once the receipt window closes — or
发送失败: <reason>. The input box is cleared only on success, so a failed send does
not cost the sentence, and the receipt never blocks the next one. Every camera
object is opened, used and closed on one
`HandlerThread`; `onPause` closes the session, the device and the reader, and
`onResume` reopens them, because a return from the permission dialog or the home
screen does not replay the `SurfaceTexture` callback.

### 3.30 `speak`: the server voice, with `say` as the net

```
speak [voice=ID] [speed=0.5-2.0] TEXT
```

`say` (§3.9) is the phone's own engine: always there, and it sounds like it.
`speak` sends the sentence to the Fish TTS service on the VPS instead, plays the
MP3 that comes back, and falls back to `say` when anything along that path
fails. The point is not a new verb for its own sake — it is that a voice worth
listening to is worth a network round trip, and a sentence that has already been
asked for must not be lost because a server was busy.

- `TEXT` is the rest of the line **verbatim** (inner spaces kept), the same
  leading-only rule as `say`: `speak hi speed=2` says "hi speed=2".
- `voice=ID` overrides the baked default (`phoneagent.ttsVoice` in
  `local.properties`); `speed=` is clamped 0.5..2.0 (default 1.0) rather than
  refused — the operator asked for faster, not for an error. Anything else that
  starts with `voice=` or `speed=` and does not parse is a usage error, because a
  flag that was meant as a flag and silently read as text is a sentence nobody
  asked for.
- `TEXT` is clipped by `SayPlan.clipText` to 2000 chars, then cut into chunks:
  **the first at most 40 chars, the rest at most 120**, preferring a sentence end
  (`。！？；…` or a newline), then a clause end (`，、：,:;`), then a hard cut that
  never splits a surrogate pair. The first chunk is short because time-to-first-
  sound is what the person holding the phone judges; the rest are long because
  every extra request costs another round trip. 500 chars is the server's own
  cap — a longer body is refused with 413, which is why the chunker never emits
  one.

**Wire.** `POST {ttsBase}/tts/` with `X-TTS-Secret: <secret>`,
`Content-Type: application/json; charset=utf-8`, `Accept: audio/mpeg`, and the
hand-written body `{"text":"…","voice":"…","speed":1.00}` (field order fixed,
quotes and control characters escaped — `SpeakPlan.requestBody`). 200 is
`audio/mpeg` bytes; anything else is JSON with `error` and `detail`, and both are
put in the receipt (`服务端 502（upstream timeout）`) because the operator's next
move depends on which of the two it was. Connect timeout 10 s, read timeout 90 s:
a chunk is a short sentence, but a cold free-tier model can take most of a minute.

**Pipeline: synthesis is parallel, delivery is strictly ordered (v0.11.3-pad12).**
`SynthBoard` (its own file, plain JDK — no Android, no dependency) runs
`SYNTH_WORKERS = 3` daemon threads named `speak-synth-0..2`. Each takes the next
index, POSTs it, writes `cacheDir/tts-<index>.mp3` and parks the result in the
board; the poll thread asks for index 0, then 1, then 2 — in order, whatever order
the synthesizers finished in — so playback order is text order and an early finish
can never reorder a sentence. At most `QUEUE_CAPACITY = 4` finished chunks wait for
the player, so a 30-chunk paragraph never buffers 30 MP3s.

One synthesizer was not enough, and the numbers are why: synthesis costs ~0.10 s
per character while playback reads 4-5 characters a second, so a 120-character
chunk needed ~12 s behind a 40-character chunk that played for ~10 s — **every
sentence boundary was a several-second silence**, and the 300 ms settle per chunk
added to it. Three requests at once is what the VPS already serves without
queueing.

Every wait is bounded. A worker that never returns is not waited on forever:
`take(SYNTH_WAIT_MS)` gives up after **110 s** (`CONNECT_TIMEOUT_MS` +
`READ_TIMEOUT_MS` + 10 s) and reports `合成卡死（已等 110.00s）` through the same
fallback/partial path as any other failure. That bound is not theoretical — ten
serial syntheses against the VPS measured 2.3-2.9 s each **except the seventh,
which took 80.8 s**; a request that slow is already past the socket timeouts and
comes back as a failure, so this catches only a connection that never returns at
all. `stop()` interrupts every worker and joins each one for at most 1 s, so a
worker blocked inside a socket read cannot hang the command either.

Each chunk is played by a fresh `MediaPlayer` (`USAGE_MEDIA` +
`CONTENT_TYPE_SPEECH` — `setDataSource(path)`, synchronous `prepare()`, `isPlaying`
polled every 50 ms after a 300 ms settle), then deleted. Playback is capped at the
file's own duration + 5 s, or 120 s when the duration is unknown: a player that
never reports `false` is a hung poll loop, and this one is shared with every other
command.

**Short text is one request (v0.11.3-pad12).** `chunks()` returns the text as a
single piece when it is at most `SINGLE_SHOT_MAX = 80` characters, and that path
starts **no worker thread at all**: `fish()` synthesizes and plays it on the poll
thread, because three threads for one chunk would be three threads with nothing to
do. Cutting a 44-character line into 40 + 4 used to buy an earlier first sound at
the price of an extra round trip (1-2 s of server overhead), an audible join, and a
4-character tail synthesized on its own — without the intonation of the sentence it
was cut from. One request for 80 characters is ~8 s before the first sound, and the
owner said he can wait; 80 is the balance point, since beyond it the first sound
would be past 10 s. Above 80 the old ladder applies unchanged (first chunk ≤ 40,
the rest ≤ 120).

**Fallback, and when it is refused.** Nothing configured (a clone of this repo
builds with empty `BuildConfig` values), an empty chunk list, or a failure before
any audio was heard ⇒ the whole text goes through `SayOps.speak` and the receipt
says so: `speak 已朗读 24 字（回落系统 TTS：服务端 502）`, exit 0. A failure
**after** some audio played is *not* retried — replaying the paragraph to cover
the last sentence would say the first ones twice — so it answers
`speak 已播 2/3 块，第 3 块失败：服务端 502，未回落（避免重复朗读）` with
`exit=6`. A fallback that also fails answers `错误: speak 回落系统 TTS 也失败：…`
with `exit=6`. Both use 6 because it is the closest of the seven existing codes to
"the far end refused" — no eighth code is invented, since every caller switches on
this table.

Success is one line, and every number in it is one the server or the player
actually produced: `speak 已朗读 240 字（fish voice=7f92 speed=1.00 3 块 首声
2.40s 总 14.80s 712000B）` — bytes printed exactly, not rounded to KB, so the
receipt can be compared with the server's own log.

**Why `USAGE_MEDIA` and not `USAGE_ASSISTANT` (v0.11.1-pad12).** The usage
attribute decides which stream the audio is played on, and that stream is the one
the owner adjusts with the volume keys. Measured on the operator's MEIZU 22
(Android 16) with `dumpsys audio`:

| Attribute | Stream | Max | Speaker was at |
|---|---|---|---|
| `USAGE_ASSISTANT` | `STREAM_ASSISTANT` | **15** | **11** (73%) |
| `USAGE_MEDIA` | `STREAM_MUSIC` | **60** | **60** (100%) |

v0.11.0 shipped `USAGE_ASSISTANT` and separately pushed `STREAM_MUSIC` to its
maximum (`dumpsys`: oldIndex 28 → index 60), so the push landed on a stream
nothing played to and the owner still heard a voice far quieter than the system
one. The system engine is not a louder source to blame: `dumpsys audio` prints
`STREAM_TTS (aliased to: STREAM_MUSIC)`, so `say` played at 60/60 while `speak`
was playing at 11/15. The Fish audio itself is fine — `ffmpeg -af ebur128` on a
returned MP3 measures I: −9.8 LUFS, true peak −0.7 dBFS.

`USAGE_ASSISTANT` is also the wrong stream to *drive*: it belongs to the phone's
own voice assistant. `CONTENT_TYPE_SPEECH` is kept — it is what tells the system
this is speech and not music. No flags are set. The usage is exposed as
`FeatSpeak.SPEAK_USAGE` (an `Int`) so a plain JVM test can pin it; the
`MediaPlayer` itself still cannot be built off-device.

**Volume: `speak` does not touch it (v0.11.2-pad12).** The owner said the level is
right and that he adjusts it himself, so the app no longer reads or writes the
phone's volume at all. What ships is one decision and no code: the audio goes out
on `STREAM_MUSIC` (`USAGE_MEDIA`, §above), which is the stream his volume keys
move and the stream the system voice already uses, so both voices are judged at
whatever level he has set.

This is a deliberate deletion, not a regression, and the history is worth keeping
because v0.11.0 did the opposite: `VolumeGuard` raised `STREAM_MUSIC` to its
maximum on the first sound and restored the old value in a `finally`, logging
`调整媒体音量失败` / `恢复媒体音量失败` on failure. It was safe (a run that never
made a sound never touched the sliders) but it was still the app moving the
owner's sliders behind his back, and after the v0.11.1 route fix the voice was
audible without it. So: no `AudioManager` in `FeatSpeak.kt`, no stream accessor,
no volume in the receipt. What the owner hears is exactly what his own volume
setting says, including 0.

**The secret never enters the repository.** `phoneagent.ttsBase`,
`phoneagent.ttsSecret` and `phoneagent.ttsVoice` live in `local.properties`
(git-ignored) and are baked into `BuildConfig.DEFAULT_TTS_*` at build time, the
same mechanism as the relay base and secret. What this costs is stated plainly:
the text being spoken leaves the phone, and `network_security_config.xml` must
already allow that host — `say` remains the offline answer.

### 3.31 `cam-open` / `cam-close`: when the camera is on

Not a verb — two note kinds. 摄像头看图 (§6.3) posts one note when the page becomes
visible and one when it stops being visible, so the far end can tell "the camera is
on right now" from "the last frame arrived five minutes ago". A `look` note alone
cannot say that: it is written when 发送 is pressed, which is also true of a page
that was closed ten seconds later.

**Body.** `POST /agent/note` with exactly two fields —
`{"id":"<agentId>","kind":"cam-open"}` (`CamPlan.modeBody`). No `text`, no
`blob`, no `w`/`h`: the relay stores `kind` verbatim (truncated at 24 chars),
takes `blob` as optional and defaults the size fields to 0, so a two-field body is
a complete note rather than a malformed one.

**Only two words.** `CamPlan.modeBody` copies `kind` only when it is exactly
`cam-open` or `cam-close`, and writes an empty string otherwise; `CamPlan.modeNote`
is what the caller checks first. The camera is a privacy switch, so a typo must not
reach the operator's log as a third state nobody can explain later — a state that
never gets sent is recoverable, a state that gets sent is not.

**When.** `onResume` → `cam-open`, `onPause` → `cam-close` **before**
`closeCamera()`: the note says "the camera is closing" and has to leave while that
is still true. A `@Volatile modeOpen` flag pairs them, because `onResume` can run
twice with no `onPause` in between (returning from the permission dialog), and a
second `cam-open` reads as a second opening of a camera that never closed.

**Off the UI thread.** The note is posted from a `Thread("cam-mode")`, like every
other network call on this page: blocking in `onResume`/`onPause` blocks the
page. A failure only goes to the log (`相机状态上报失败(cam-open): <reason>`) — this
is a side note about the camera, not the frame the owner pressed 发送 for, so it must
not put an error banner over the preview. The page builds its own `RcClient`, so
these notes work with the agent stopped, exactly like `look`.

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
- `flags` = concatenated letters, only those true, always in this order:
  `c`=clickable `l`=long-clickable `f`=focusable `e`=editable `s`=scrollable
  `x`=checked `d`=disabled `k`=selected, then `z`=no usable bounds (点不了).
- `z` is appended **last** so that every older letter keeps the position it has
  always had and a parser written against the previous format keeps working.
- `z` means the printed rect is one `click` refuses: `right <= left` or
  `bottom <= top`, which is what `ElementActions.centreOf` returns null for. Real
  samples: `52,254,1148,86` (bottom above top, height negative) in 知乎 on the
  phone; 58 of 72 text nodes on one 糖豆 page and 97 of 163 on a 百度 page on the
  tablet were zero-area. A node with **no** rect at all prints an empty bounds
  column and is *not* marked — there is nothing misleading to flag, and the column
  already says it.
- Those nodes are **marked, never filtered** (v0.8.3-pad12). Their text is still
  evidence of what is on this screen, and on a busy page they are the majority of
  the text nodes — dropping the lines would throw most of the content away. What
  the caller needs is not a shorter tree but to see at a glance which nodes `click`
  can reach, while still being able to read the ones it cannot. A `z` node is still
  clicked through its own `ACTION_CLICK` first (`clicked via=action`); once that
  fails there is no centre to tap, and `click` answers exit 5 — either the plain
  `node has no usable bounds` (no clickable ancestor with a usable rect that also
  overlaps the node), or that line extended with `; nearest clickable ancestor is
  <class> "<text-or-desc>" at <l,t,r,b> — add \`up\` to tap it`. The ancestor is
  **named, not pressed**: the opt-in switch is `click up SEL` (§3.1), because tapping a
  container is a guess about which control the label belonged to. The geometry rule
  (v0.8.5-pad12, §3.1) is what keeps that guess on a container that really holds the
  label: a page a WebView drew can put a node nowhere near its parent chain, and the
  nearest clickable ancestor can then be a control on the other side of the screen.
- Skip nodes that have no text, no contentDescription, no resource id **and** no
  flags — unless they have children (walk through them without emitting a line).
  `z` alone does **not** count as a flag here: an empty container with a zero-area
  rect is still an empty container, and printing those would add blank lines to every
  busy screen without telling anyone anything. `z` only annotates lines that were
  already worth printing.
- Enumerate **all** windows (needs `flagRetrieveInteractiveWindows`), each
  starting at depth 0. The **active** window is read through `rootInActiveWindow`,
  not through `AccessibilityWindowInfo.getRoot()`: on Flyme / Android 16 the
  latter hands back a cached snapshot, so a panel that expands inside the window
  already on screen never appears in it — measured on the real phone, pressing
  高级设置 drew three `EditText`s and `tree` kept reporting zero after one second,
  after six seconds and after a scroll; only switching away and back made them
  visible. Every root is `refresh()`ed first (a failure just means the node went
  away and it is used as-is). The remaining windows are read through
  `window.root`, ordered top-of-screen first, and the active window is never
  emitted twice: `WindowPicker.merge` keeps the fresh root first, then the
  remaining windows top-of-screen first, de-duplicated by window id.
- The screen is read **twice**, 150 ms apart, and the second answer is reported
  when the two disagree. This is the self-healing half: a layout that lands
  between the two reads becomes visible, and a second read that times out never
  turns a good first read into a failure. One retry only — `tree` is run in a
  loop while the operator pokes the screen, so it may not become slow.
- If the tree is larger than `maxNodes`, stop and append a final line
  `... truncated at <n> nodes`.
- Accessibility service not connected → `exit=3`, stderr `accessibility service not connected`.

### 5.1 `tree uia`: the same tree read as uid 2000 (v0.6.1-pad12)

`tree` without arguments keeps using the accessibility channel. On this phone
that channel can go stale — the framework only keeps a window's content cache
current for the event types the service subscribed to, and until v0.6.1-pad12
the service subscribed to window changes only (`accessibility_service_config.xml`).
The config now also subscribes to `typeWindowContentChanged` / `typeViewClicked`
/ `typeViewFocused` / `typeViewScrolled`, and `onAccessibilityEvent` stays empty:
we collect the events and do nothing with them. That is mechanism A.

Mechanism B is the escape hatch, and it is the one that cannot be defeated by a
ROM's accessibility cache:

```
tree uia [maxNodes]
```

1. Requires the self-ADB link (`SelfAdb.isConnected`). Without it the answer is
   `exit=6` and names the fix (`shconnect`) — never a silent fallback, because a
   fallback would hand back the very stale tree this command exists to escape.
2. One shell round trip (`UiaDump.dumpCommand`): `uiautomator dump
   /data/local/tmp/phoneagent-tree.xml`, then `cat` it back in the same line, so
   the PTY is crossed once (~1 s) instead of twice. Budget 15 s
   (`Executor.UIA_TIMEOUT_MS`); the dump itself measured 1–2 s.
3. The file is **left in place** on purpose — it is the evidence an operator
   needs when the two channels disagree. Read it with `sh cat
   /data/local/tmp/phoneagent-tree.xml`.
4. `UiaDump.parse` turns the XML into the format above, through the same
   `UiTree.format` the accessibility path uses, so flags, the 60-char field
   clipping, the `|`/`\n` neutralising and `... truncated at <n> nodes` are
   identical by construction. Two differences the XML forces:
   - `depth` is not an attribute. The parser keeps a stack of open `<node>` tags
     and attaches every node to the nearest enclosing one, so depth is the length
     of that stack (the `<hierarchy>` element itself is not a node).
   - `editable` is not an attribute either. A node counts as editable when
     `editable="true"` or when its class ends in `EditText` — the accessibility
     channel reports `e` for every `EditText`, so the two channels must agree.
5. `uiautomator` is uid 2000 only; the app's own uid cannot run it. That is why
   this needs `shconnect`, and why it is the trustworthy reading when `tree`
   and `uiautomator dump` disagree.

Measured on the real phone (Flyme / Android 16, 2026-11): with 高级设置 expanded
and then collapsed, `tree` reported 3 `EditText` in both states (one window
activation behind), while `tree uia` reported 3 then 0 at the same moments.

---

## 6. UI (`MainActivity`)

One screen, `android.app.Activity`, plain `LinearLayout` built in code (no XML
layout file). Since v0.5.8-pad12 it is organised for the phone's owner, not for
the operator — top to bottom:

1. **Status card (v0.6.2-pad12).** One 24 dp lamp, one big word (28 sp) and one
   small grey line, then three 12 dp lamps in an equal-width row labelled
   无障碍 / 电池 / 自连ADB. The verdict is `HealthPlan.evaluate` over four
   *freshly probed* facts and has three states:

   | lamp | word | colour | when |
   |---|---|---|---|
   | green | 运行中 | `#2E7D32` | service running and all three probes fine |
   | amber | 部分异常 | `#F9A825` | service running, at least one probe not fine |
   | red | 已停止 | `#C62828` | service not running, **whatever the other three say** |

   The small line names **only the things that are wrong**, in a fixed order
   (无障碍 → 电池 → 自连 ADB), and says 一切正常 when nothing is: a list of four
   facts forces the owner to work out the verdict herself, and an `issues` set
   would reorder itself between builds. A stopped service is red even when the
   other three are green — calling that 部分异常 would read as "still usable" and
   nobody would press 启动. All three missing is still amber, never red. The tail
   of the line names the one repair that actually helps: 建议点下面的一键设置权限
   when a permission is missing, 建议先 shconnect 连上自连 ADB when only the ADB
   link is down (the wizard does not touch ADB, so pointing at it would look
   broken).

   The four probes are read again on every refresh — `RcAgentService.running`,
   `Admin.accessibilityEnabled(this)`, `Battery.isExempt(this)`,
   `SelfAdb.isConnected()`. None of them is cached, because two of them change
   without notifying us (the ROM can revoke accessibility and the battery
   whitelist), and a cached green lamp is worse than no lamp. All four are local
   reads: no network, no disk, nothing that can block the UI thread.

   Refreshed once in `onResume` and then every **3 s** while the page is in front;
   `onPause` clears the callback *and* a `@Volatile foreground` flag makes an
   already-queued run a no-op, so nothing probes the phone while the page is
   behind another one. The service's heartbeat thread is untouched by this: it is
   blocked on a socket, and the UI thread does no I/O to compete with it.
2. **The one main button**, `启动 / 停止` (`UiText.START` / `UiText.STOP`) →
   starts/stops `RcAgentService`, and **writes the auto-start flag in the same
   press**: stopping sets `auto_start = false` *before* the service is touched,
   starting sets it `true`. The decision itself is `StartPolicy.onToggle` (§7),
   so the button and `BootReceiver` can never disagree. Without this, "stop" was
   a suggestion: a reboot, an app update or `START_STICKY` would bring the agent
   back on a phone nobody was watching.
3. **One switch.** `屏幕常亮` is the same switch as `screen stayon` (§3.14): it
   calls `ScreenOps.stayOn` — never a second write path — and then re-reads
   `Prefs.stayOn`, so a refused write leaves the box unchecked instead of showing
   a state that is not in force. A hint line under it carries `StartPolicy.HINT`:
   点「启动」后它一直运行到你点「停止」；手机重启后不会自己起来，需要再点一次「启动」。

   v0.6.3-pad12 removed the second switch, `开机自动启动`. It was never a
   separate decision: `Prefs.autoStart` is written by the 启动/停止 button itself
   (`StartPolicy.onToggle`) and read by `shouldStartOnOpen` and
   `shouldDisarm`, so the checkbox could only ever show — or fight — what the
   button had already decided. The owner asked for "开机不自启，要人工点启动";
   with the boot start gone (§7) the switch had no state left to display, and a
   checkbox that does nothing is worse than no checkbox. The **flag** stays
   (key name and default untouched — renaming it would silently reset every
   installed phone), and `toggleAgent` keeps writing it: it is still the gate
   that stops a relay `stop` or a `START_STICKY` restart from reviving the
   guard (§3.17).
4. **`一键设置权限` — one wizard instead of four buttons.** Four buttons asked the
   owner to know *which* permission was missing. The wizard asks `PermPlan.next`
   instead and does exactly the next missing thing, so pressing it repeatedly
   walks the list: runtime permissions → accessibility → battery whitelist →
   权限已齐全. The order is not cosmetic: location comes first because the
   background grant is a two-pass question (§2 of `manualcheck.md`), and a
   settings screen opened in the middle of it means the second question is never
   asked. The line under the button (`PermPlan.hint`) says what is still missing.
   - runtime → `requestPermissions` with `POST_NOTIFICATIONS` (API 33+),
     `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, then (second pass)
     `ACCESS_BACKGROUND_LOCATION`
   - accessibility → `ACTION_ACCESSIBILITY_SETTINGS`
   - battery → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with
     `package:<applicationId>`; if the ROM refuses the intent, fall back to
     `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
5. **`高级设置` — collapsed by default.** Relay base URL (`EditText`, default
   `https://relay.2-c.cc`), Agent id (`EditText`, default `PHONE-MEIZU`), Secret
   (`EditText`, `inputType=textPassword`) and `保存` → persists to
   SharedPreferences. The section's `visibility` is `GONE` until the title is
   pressed and is **not persisted**: every visit starts collapsed, so a stray tap
   cannot leave the secret field on screen. Saving only writes prefs; it never
   restarts the service behind the owner's back.
6. **The version line**, `v` + `BuildConfig.VERSION_NAME`, with no hint text —
   and the five-press door to `DebugActivity` (§6.1).
7. Opening the UI starts the service when `StartPolicy.shouldStartOnOpen` says so
   (§3.17). This is not a convenience: Flyme delivers no package-replaced
   broadcast, so after a silent `pm install -r` the only signal the app ever gets
   is somebody bringing the activity up.

Every clickable label is defined once in `UiText` (`START`, `STOP`, `SAVE`,
`AUTO_START`, `STAY_ON`, `PERMISSION`, `ADVANCED`) because the operator drives
the real device with `click apply <text>`, not with coordinates: two controls
with the same text is a mis-click, and a label that changes with state is a
control that cannot be found. `UiText.clickable` is asserted unique in
`PermPlanTest`.

The non-clickable labels (`RELAY_LABEL`, `ID_LABEL`, `SECRET_LABEL` and the
three lamp labels) live there too, and `HealthPlanTest` asserts that no lamp
label *contains* a clickable label or vice versa. That is not pedantry: the
matcher is `text.lowercase().contains(needle)` (§3.1), so a lamp labelled
无障碍 would be found by `click apply 一键设置权限`'s neighbours and, worse, a
`click apply` whose needle is a substring of a lamp label reports 只匹配到不可点
文本 instead of pressing the button.

Buttons must not crash when the service was never started.

### 6.1 Two screens, two people (v0.5.7-pad12)

The one screen above had grown twelve buttons for two different jobs, so it is
now split in two. The split is a *door*, not a permission: same APK, same
process, same executor.

- **`MainActivity` — the config page.** Everything in §6 stays exactly where it
  was, plus one new line at the very bottom: `v` + `BuildConfig.VERSION_NAME`
  in a plain `TextView` with no hint text of any kind. Five presses within 3 s
  (`TapGate(5, 3_000)`, timed with `SystemClock.elapsedRealtime` — a wall clock
  can jump and strand the door shut) start `DebugActivity`. A gate that fires
  empties itself, so a stray tap afterwards is just a tap.
- **`DebugActivity` — the operator's page.** `exported="false"`,
  `excludeFromRecents="true"`, not in the launcher, built in code like the config
  page (`ScrollView` + `LinearLayout`, no XML). Four blocks:
  - **A. Summary**, refreshed every 2 s: version, service running/stopped,
    accessibility on/off, battery percent (`batteryPercent`), self-ADB
    connected/not (`SelfAdb.isConnected`). All static reads — no network.
  - **B. Command panel.** One `EditText` and 执行 / 清空. The line goes through
    `Commands.parse` and the **live service's own** `CommandExecutor` via
    `RcAgentService.executeLocally`, so the page tests the same path the relay
    drives — not a second copy of it. The answer is `exit=N` first, then stdout,
    then stderr, in a monospace `TextView`. Off the UI thread always: `tree`
    waits on the main looper, `shot` uploads a blob, `logs` walks logcat.
  - **C. Quick actions:** `shot`, `tree 30`, `tree uia 30`, `logs 200`,
    `logs size`, `logs trim` (dry run), `logs trim apply`,
    `Diagnostics.run` (≈1 min, copied to the clipboard, previewed),
    `Diagnostics.stateOnly` (zero network, copied), clear the log.
  - **D. 返回配置页** → `finish()`.
- **Answers are clipped at 2000 chars** (`DebugPanel.PANEL_MAX_CHARS`) and the
  cut says so: `…（已截断，共 N 字符，只显示前 M 字符）`. A 190 000-char stdout
  would freeze the UI thread, and a silently shortened answer is worse than a
  marked one. The cut never lands inside a surrogate pair.
- **No service instance → no result.** `RcAgentService.executeLocally` returns
  null when `current` or its `executor` is gone, and the panel then says
  服务未运行，先回配置页点启动. There is deliberately no fallback that builds a
  second `CommandExecutor`: that would mean a second `SelfAdb.init`, a second
  location helper and a screenshot path that disagrees with the relay's.
  `CommandExecutor` has no mutable fields (five `val`s plus a main-thread
  `Handler`), so two callers cannot corrupt each other — the caller only has to
  stay off the UI thread.

### 6.2 The third screen: 视频转音乐 (v0.7.0-pad12, re-count on resume v0.7.3-pad12)

A third Activity, and the first one that belongs to the **owner** rather than the
operator. Reached by one button on the config page (`UiText.CONVERT`,
视频转音乐), `exported="false"`, `excludeFromRecents="true"`, built in code
like the other two.

It shows exactly what the owner needs to decide whether to press anything:

1. The source folder, selectable so it can be copied: `/sdcard/视频转音乐专用`.
2. One line of counting, from a fresh `Mp3Converter.scan` **every time the page
   is resumed**, not just when it is created (v0.7.3-pad12):
   视频 N 个：待转 A 个，已有 mp3 B 个，无音轨 C 个. An empty folder says so
   instead of showing 0/0/0.
3. **开始转换** (`ConvertActivity.START_LABEL`) — deliberately *not*
   `UiText.CONVERT`: the config page's button already owns that string, and
   `click apply 视频转音乐` must not have two candidate targets. Disabled while
   nothing is pending, and disabled while a batch is running.
4. **刷新** (`UiText.REFRESH`) between 开始转换 and 返回 — the manual half of the
   same re-count. See "the page must notice files that arrived while it was open"
   below.
5. One progress line, replaced as the batch advances (latest line wins).
6. The full per-file list in monospace once the batch ends. Its last line *is*
   the summary (`完成 N 个，失败 M 个，跳过 K 个，无音轨 J 个，用时 X.Xs`, §3.20),
   and the page does not restate those numbers anywhere else — one source, so the
   two can never disagree. After that it re-scans, so a batch that converted
   everything immediately shows 没有要转的 instead of yesterday's count.

**The page must notice files that arrived while it was open** (v0.7.3-pad12).
This is the owner's actual order of operations, reported from the tablet: open
视频转音乐 first, then go copy a video into `/sdcard/视频转音乐专用` with the file
manager, then come back. Coming back only *resumes* the Activity — `onCreate` does
not run again — so the page kept showing the count it took on the way in:
没有要转的, button disabled, nothing to press. The only way out was to leave the page
and re-enter it, which the owner has no reason to guess.

So the re-count hangs off `onResume()` rather than `onCreate()`: after `onCreate`
the framework always calls `onResume`, so the first screen is not missed either,
and there is exactly one place that decides when to count. The 刷新 button is the
fallback for the case where the automatic path does not fire (a ROM that resumes
without the folder change being visible yet); it is the same call, so the two can
never disagree.

`refresh()` is guarded by a `scanning` flag: the three sources — resume, the
button, and the re-count after a batch — can land at the same moment, and three
scan threads would only race to write the summary, with the last one winning
regardless of whether it read a newer folder. While `running` is true it does not
scan at all: the numbers on disk are moving, so a count taken mid-batch is stale
before it is drawn. 刷新 is disabled for the same two reasons, and re-enabled from
the scan callback by reading `running` rather than assuming it.

### 6.3 The fourth screen: 摄像头看图 (v0.9.0-pad12)

One button on the config page (`UiText.CAMERA`, 摄像头看图, directly under
视频转音乐 and styled the same way), `exported="false"`, `excludeFromRecents="true"`,
built in code. The protocol and the capture decisions are §3.29; this is only what
is on the screen.

Top to bottom: one slim camera switch (v0.11.3-pad12, right-aligned; a switch
rather than a way out since v0.11.4-pad12), the live preview (a `TextureView`
inside a `FrameLayout`, both at layout weight 1, so they take everything the text
box, the status line and the send button do not), one status line, the text box,
and 发送.

1. **The preview.** Back camera, repeating `TEMPLATE_PREVIEW` request. It is the
   reason the page exists — the person points the phone at the thing instead of
   describing it.
2. **The status line**, one line, never more, and **it says which phase you are in**
   (v0.11.3-pad12). 对准要我看的东西，打一句话，按发送 on arrival; then
   `CamPlan.HOLD_TEXT` 正在拍，先别动 in orange-red (`0xFFFF6B00`, 18 sp) while the
   sensor is integrating — this is the only stretch where the frame can be ruined
   by moving; then, the instant the JPEG bytes land in the `ImageReader`,
   `CamPlan.DONE_HOLD_TEXT` 拍好了，可以动了（正在发送） in green (`0xFF12A150`) plus
   one 70 ms vibration; finally either
   `已发送：图 148 KB（1280x720）+ 文字 12 字` in the idle grey or
   `CamPlan.retryLine()` 没传上去，再按一次发送 in red (`0xFFD32F2F`) with two short
   vibrations. The phase change is driven by the bytes arriving, **not by a timer**:
   a timer would either tell the owner to move while the sensor is still reading or
   make him wait for an upload that is none of his business. The failure line says
   what to do next and the exception text goes to `AgentLog` — a person holding a
   phone cannot act on `SSLException`, and the sentence he typed is still in the box.
   Every failure funnels through one `reportFailure` exit, so "press send again" is
   said once, in one wording.
3. **The text box** — multi-line visually (`setSingleLine(false)`, `minLines = 2`,
   `maxLines = 4`), `maxLength = CamPlan.MAX_TEXT` (500 code points, so the limit the
   input filter enforces is the same number the send clamps to), hint
   说一句：哪里不对 / 要我看什么. **Enter sends** (v0.11.3-pad12):
   `imeOptions = IME_ACTION_SEND` with an `OnEditorActionListener` **and** an
   `OnKeyListener` for `KEYCODE_ENTER`, because some IMEs report the soft key as an
   editor action and others as a raw key event. `TYPE_TEXT_FLAG_MULTI_LINE` is
   deliberately absent: with that flag every IME turns its action key into "insert a
   newline" and never calls the editor action, so Enter would break the line instead
   of sending. The visual multi-line comes from `setSingleLine(false)`, which is
   independent of the flag. A double fire is harmless — `send()` returns at once
   while `busy`. The phone's own IME voice input is still the expected way to fill
   the box; the page does nothing about the keyboard except wait for it to leave
   (300 ms, §3.29).
4. **发送**, full width, the only button in the bottom row. It is disabled while a
   send is in flight — a second press would capture a frame nobody asked for and
   upload it under a second name.
5. **The camera switch** (v0.11.3-pad12, a switch since v0.11.4-pad12), a slim
   right-aligned button **above** the preview. It replaced the old 返回 in the bottom
   row: 返回 sitting next to 发送 read like "go back one step" and was pressed by
   mistake. It is **not** `finish()` any more, and that correction is the whole of
   v0.11.4 — see "The switch is not an exit" below.

   It has exactly two states, and the label always names the state the next tap
   leads to, never the one the page is in:

   | `cameraOn` | Button | Preview | 发送 |
   |---|---|---|---|
   | `true` (default) | 关闭摄像头 (`CamPlan.CLOSE_CAMERA_TEXT`) | live repeating preview | capture + text (§3.29) |
   | `false` | 打开摄像头 (`CamPlan.OPEN_CAMERA_TEXT`) | `CamPlan.CAM_OFF_TEXT` 摄像头已关闭，这次只发文字 in the idle grey, centred | text only — no capture, no `SETTLE_MS`, no buzz |

   `toggleCamera()` flips `@Volatile cameraOn`, repaints through one
   `applyCameraSwitch()` (button text plus placeholder visibility, both in one
   place so they can never disagree), and then calls the **existing** `closeCamera()`
   or `openCamera(texture)` — the same teardown `onPause` uses, so a session, device,
   reader and surface still die in exactly one place. The page does not go anywhere;
   leaving is the system back gesture.

   The switch is **enabled during a send but `toggleCamera()` returns at once while
   `busy`**: pulling the camera out from under `captureStill` would turn a send in
   flight into a mystery, while a switch that looks dead for the two seconds of an
   upload is a switch the owner presses twice. Ignoring one tap is the smaller lie.

   `cameraOn` outranks the lifecycle. `onPause` still releases the camera and posts
   `cam-close`; `onResume` re-opens **only if `cameraOn`** (`if (started && cameraOn)`),
   and `onSurfaceTextureAvailable` guards on it too — otherwise a config change or a
   return from the home screen would light the camera while the button says it is
   off. If the camera cannot come back (no permission, taken by another app, no
   surface), the failure goes through `cameraFailed()`: the red line as usual, and
   then `cameraOn = false` plus a repaint, so the button reads 打开摄像头 and the
   placeholder is visible. The page must never claim a camera it does not have.

   **The switch is not an exit.** v0.11.3 shipped this button as `finish()` and the
   owner corrected it in his own words: 关闭摄像头 means "stop capturing pictures, so
   the next 发送 goes out without one", not "go back to the config page". He was
   describing the thing he wanted — a send with no picture — and `finish()` was the
   wrong mechanism for it: it made him re-enter the page to send one sentence with a
   picture afterwards, and it left the far end with no way to tell "camera released
   on purpose" from "page closed". Releasing the camera in place keeps both facts
   true at once, and it is the only reading under which the two labels above make
   sense.

The labels live in `CameraActivity`'s private companion (`SEND_TEXT` = 发送) and in
`CamPlan` (`CLOSE_CAMERA_TEXT`, `OPEN_CAMERA_TEXT`, `CAM_OFF_TEXT`, `HOLD_TEXT`,
`DONE_HOLD_TEXT`, `retryLine()`, `textOnlyLine()`),
deliberately not `UiText` constants — `click apply 发送` on this page must have
exactly one candidate, the same rule §6.2 states for 视频转音乐.

**No title bar** (v0.11.3-pad12). This page and the config page both carry
`android:theme="@style/NoTitleTheme"` — a `Theme.DeviceDefault.DayNight` child with
`windowNoTitle` and `windowActionBar` both false (`res/values/styles.xml`). Not
`Theme.DeviceDefault.DayNight.NoActionBar`: that variant is not guaranteed to exist,
and a missing parent is a runtime fallback rather than a build error. Hiding it in
the theme rather than in code also means `MainActivity` needs no change — the title
was never its view, it was the ActionBar's. The pixels go to the preview, which is
the only part of the page that gets better with more room.

`android:screenOrientation="portrait"` (v0.9.1-pad12). The page is portrait, always,
even when the rest of the system is locked landscape. Two reasons, and the first one
is the owner's: this is a page for pointing a phone at a shelf, held upright — a
rotated page puts the text box sideways and the preview in a letterbox 575 px tall.
The second is that rotation is a *rebuild*: the activity is recreated, the camera is
closed and reopened, and the still that was in flight dies with the old instance.
A preview that turns because the phone tilted while the person was typing is a page
that loses the sentence. Everything else in the app follows the system rotation;
this one screen does not.

`FLAG_KEEP_SCREEN_ON` is set for the life of the page: the owner is holding the
phone up at something, and a screen that sleeps mid-sentence loses the sentence.
This is the one place in the app where keeping the screen awake is what the person
wants rather than a battery decision.

The page also **says when it is showing the camera** (v0.11.0-pad12): `onResume`
posts a `cam-open` note and `onPause` posts a `cam-close` one, before
`closeCamera()` — see §3.31 for the body and the two-word rule. They exist because a
`look` note only says "a frame was sent", which is equally true of a page closed ten
seconds later; with the pair, the far end can tell a camera that is on now from one
that was on this morning. They are posted from `Thread("cam-mode")` and a failure is
logged, never shown: a note about the camera must not put an error banner over the
frame the owner pressed 发送 for.

Everything camera-shaped runs on one `HandlerThread` (`cam-look`), never the main
thread: `openCamera`, the session callbacks, the still capture and the
`ImageReader` callback are all posted there, and the main thread only ever touches
the views. The one exception is `TextureView.getSurfaceTexture`, which is only
legal on the UI thread — so the surface is taken there and handed to the camera
thread. Decoding, re-encoding and the two HTTP calls happen on a plain
`Thread("cam-send")`, because the preview has to keep running while they do.


Two details that are not cosmetic:

- **The button is disabled while a batch runs.** Two presses would start two
  batches racing over the same `<name>.mp3.part`, and the loser would rename a
  half-written file over the winner.
- **A batch re-scans before converting** instead of reusing the numbers on
  screen: the owner may have added or deleted files since the page opened, and a
  stale list produces a run of "file not found" failures for no reason. A scan is
  tens of milliseconds.

The page runs the **same** `Mp3Runner.run` as `mp3 run apply` (§3.20); the only
difference is the `onLine` callback, which streams each line to the UI. There is
no second conversion loop in this Activity.

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
- **Nothing starts the service by itself.** Since v0.6.3-pad12 `BootReceiver`
  only *repairs* the phone (stay-on timeout, accessibility binding, wireless-ADB
  port) and never calls `startForegroundService`; `BootPlan.steps` has exactly
  three steps and `BootPlanTest` asserts a plain boot returns only
  `DiscoverAdbPort`. The two ways back are the 启动 button and `onCreate`'s
  `StartPolicy.shouldStartOnOpen`, which is still gated on `auto_start` so that a
  phone the operator silenced cannot be revived by somebody opening the app.
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
- The uid-2000 guard (§3.17) is armed by `start()` on a worker thread, re-checked
  by the heartbeat at a 5 min cadence, and disarmed in `onDestroy` **only when**
  `WatchdogPlan.shouldDisarm(prefs.autoStart)` — a `START_STICKY` restart must not
  tear down the guard that is about to revive it.
- `stop` command → send the result, then `stopSelf()` — **after** applying
  `StartPolicy.onRemoteStop()`, which clears `auto_start` exactly like the button
  (§3.17). `stop` is a stop, not a pause: the guard is disarmed in `onDestroy`
  because `shouldDisarm` now sees `autoStart=false`.
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
 12. The v0.4.3 repairs, both of which failed silently before: `BootPlan` (a
     plain boot never rebuilds; `stayOn` restores the timeout; the full order) and
     `A11yOps` (a fake `SecureSink`
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
 15. The v0.5.1 escape hatch: `SelfAdbPlan.pemProblem`/`pubProblem` rejecting a
     blank body, a missing `-----BEGIN PRIVATE KEY-----`, a truncated PEM, illegal
     base64, a 100-byte blob and a 32-word (1024-bit) modulus header, while
     accepting a well-formed pair; `pubBlob`/`pubName` splitting the adb line at
     the first whitespace; `originLabel` never guessing a key that predates the
     origin file; `showReport` always printing three lines and `(未生成)` when
     empty; `adoptUnsupported`/`hiddenApiBlocked` naming the next command; and
     `isHiddenApiBlock` walking the cause chain to recognise a `NoSuchMethodException`
     about `exportKeyingMaterial` wrapped in an `SSLException` while leaving an
     ordinary handshake failure alone. `ShKeyCommandsTest` pins the grammar:
     `show`/`adopt` refuse arguments, `import` needs an http(s) URL and accepts an
     optional second one, and the usage line names all four verbs.
  16. The v0.5.2 destination fix: `shellCommand` always starts with `shell:` (the
      regression that made every `sh apply` exit 4), and `errorText` separates a
      *rejected stream* (`ConnectException` whose message says `actively
      rejected`) from a *dead port* (`Connection refused`), so the operator is
      pointed at the connection state instead of being told to check wireless
      debugging when it is already on.
  17. The v0.5.4 PTY channel (`PtyParseTest`, 24 cases): `ptyWrite` puts the
      command and the quoted sentinel on separate lines and leaves a multi-line
      command intact; `parsePty` recovers `exit 0 / 1 / 127`, keeps multi-line
      output, handles LF-only input, strips ANSI and both prompt shapes
      (`Meizu22:/ $ ` and Flyme's `127|Meizu22:/ $ `), drops the echoed command
      and the echoed sentinel line, keeps a mid-line `__RC=9` as data, takes the
      last standalone sentinel, and returns `code == null` with the partial text
      when the sentinel never arrives. `hasSentinel` only accepts a whole line,
      `channelLine` distinguishes PTY from RAW, and `status` prints the channel.

  18. The v0.5.5 self-start pair: `StartPolicy.shouldStartOnOpen` over all four
      rows (configured × autoStart × running), including "the operator pressed
      stop, so opening the app must not revive it"; and `WatchdogPlan` — the
      generated script exits on the stop marker before it looks at anything else
      and contains no single quote (it is written with `echo '…'`), `writeCommand`
      stays on one line because the PTY shell splits on newlines, `armCommand`
      removes the marker before `setsid`, `disarmCommand` writes the marker
      **before** `pkill`, `parseStatus` ignores blank lines (the trailing newline
      used to reset `offMarker` to false), `shouldArm` refuses a second copy and
      refuses when the service is not running, and `shouldDisarm` keys off
      `autoStart` rather than the service state.

  19. The v0.5.6 remote-stop repair: `StartPolicy.onRemoteStop()` equals
      `onToggle(wasRunning = true)` (same `Decision`, `start=false`,
      `autoStart=false`), `WatchdogPlan.shouldDisarm(onRemoteStop().autoStart)` is
      true while the start branch keeps the guard, and
      `shouldStartOnOpen(configured = true, autoStart = onRemoteStop().autoStart,
      running = false)` is false — the relay's `stop` and the UI button are one
      rule, and the v0.5.5 leak (guard reviving a silenced agent 60 s later) cannot
      come back through either door.

  20. The v0.6.3-pad12 pair. `BootPlan` has exactly three steps; a plain boot
      returns only `DiscoverAdbPort`, and no action × `stayOn` combination can
      produce a fourth — the strongest available statement that nothing starts the
      service after a boot (the old `StartService` step is gone and
      `BootPlanTest` fails if it comes back). And the two guard repairs:
      `disarmCommand` uses `pkill -9` three times, 0.5 s apart, and still reads the
      process table; the script carries `# ver=<version>` on line 2 with no single
      quote in it (it is written with `echo '…'`); `parseScriptCurrent` accepts only
      whole-number lines, so the PTY's own echo of the `grep -c` command cannot fake
      a hit; `shouldArm` re-arms over a live-but-stale pid and still refuses when
      the service is down; `StartPolicy.HINT` no longer names the removed checkbox
      while still promising 启动 / 停止 / 重启.

  21. The v0.7.0-pad12 mp3 pair, split the same way as everything else.
      `Mp3Plan` (pure): the ten recognised suffixes and the ones that must not
      match (no dot, dot first, `.MP4` in caps, `.mp3` itself), `targetName`
      replacing only the last suffix, `duration` (`3:01`, `1:02:03`, `?`),
      `seconds` (always one decimal), `percent` clamped at both ends,
      `scanReport` (the three counts, the per-file state words, the empty-folder
      sentence), `dryRunLine` (`将转 N 个，跳过 M 个` **in one line**, and
      `force` moving the already-done files from 跳过 to 待转), the progress /
      result / failure lines, `summaryLine`, and `failure` carrying
      `Admin.ERROR_PREFIX`. `Mp3Runner` (pure, no decoder): the skip decision —
      no audio is always skipped, an existing target is skipped unless `force`,
      and an empty work list touches no file. And the grammar
      (`Mp3CommandsTest`): bare `mp3` is usage, `scan` takes an optional
      folder and **refuses** `apply`/`force`, `run` is dry by default,
      `apply`/`force` in either order, a folder after the switches, two folders
      is usage, an unknown verb is usage, verbs are case-insensitive.
  22. `LameMp3EncoderTest` — the only test in this repo that runs a real
      encoder, and it is still a plain JVM test: 2 s of 440 Hz sine at 44100 Hz
      stereo → `> 30000` bytes, first byte `0xFF`, second byte `& 0xE0 == 0xE0`,
      then every frame walked to the end of the stream with the frame length
      computed from the bitrate (a wrong formula makes the sync check fail on a
      perfectly good file). Plus: a mono source stays mono (the mode bits in the
      third frame byte), 48 kHz → 44.1 kHz resamples without throwing, `finish()`
      is idempotent, and 10 s of audio encodes in well under a second — measured
      at **221 ms, 45× realtime** (232 ms / 43× on an earlier run), asserted at
      better than 1/5 realtime so a 10× regression fails the build instead of
      quietly shipping. And the one test that exists because a whole release
      shipped silent: `theSampleScaleMatchesWhatLameActuallyExpects` pins
      `toLameInt` to `sample16 shl 16` (32767 → 2147418112, -32768 →
      `Int.MIN_VALUE`, 1 → 65536) and round-trips a range of 16-bit values
      through `shr 16`. Byte counts and frame sync cannot catch that bug — a
      silent MP3 is a perfectly valid MP3 — so the scale has to be asserted
      arithmetically.
  23. `DecodeShapeTest` — the decode-shape rules of v0.7.2-pad12, one assertion per
      rule and no Android imports: an HE-AAC track header of 22050 losing to a
      44100 output (the case that used to fail a good video), a float track with a
      short output read as short (androidx/media #565), nobody declaring a PCM
      encoding meaning short, a 6-channel track folded to mono by the decoder
      converting normally, an output rate of 0 falling back to the track, **a
      6-channel decoder output being refused with a sentence that names the
      channel count** rather than downmixed, everything missing landing on
      44100/2/short, and an explicit float output read as float.
  24. `UiText.clickable` is now **eight** labels and still has no duplicates
      (`PermPlanTest`, `UiTextTest`), and no label is a substring of any lamp
      label (`HealthPlanTest`) — 视频转音乐 must not be clickable by accident when
      the operator means 无障碍.
  25. `UiTextTest` — the label contract of v0.7.3-pad12, because 刷新 is the
      shortest string on the screen and `click apply` matches by substring: no two
      labels equal, no label a substring of another (both directions, all pairs),
      刷新 appears exactly once and is contained in nothing else, and the list has
      exactly eight entries so a change in count is deliberate rather than
      incidental.

  26. The v0.8.0-pad12 module foundation (`FeatureRegistryTest`): six verbs,
      all unique and lowercase; the exact list `where media battery notify wm
      autowake`; an unclaimed word still falling through to `Command.Unknown`;
      `Commands.parse("media NEXT")` producing `Command.Module("media","next")`;
      every module's bad arguments coming back as `Command.BadUsage` whose detail
      starts with `usage: `; an unregistered module answering `exit=2`
      `unknown command: …` instead of crashing; and `ping`/`wake`/`status`/`stop`
      still parsing to their own classes. `FeatureNotesTest` pins the note rule —
      a JSON receipt keeps its stdout and gets the note on stderr, a plain receipt
      gets one space and the note, an empty stdout sends it to stderr.
  27. The seven features, split the same way as everything else (pure plan object
      + a grammar test through `Commands.parse`):
      `WherePlanTest` — the five keys in order, an unknown activity as `""` rather
      than the word null, quote/backslash escaping, the real `mCurrentFocus` line
      (including its Broken-pipe tail), and null / no-slash / garbage all yielding
      `""`. `MediaPlanTest` — the five keycodes, case-insensitivity, an unknown
      action with no keycode, the shell line, the fallback line saying it is not
      guaranteed, and `key play` staying a usage error.
      `BatteryPlanTest` — the read request from no arguments, allow/deny dry by
      default, `apply` in any case, the exact `cmd deviceidle whitelist ±` line
      for our own package, normalise→parse round trip, and the dry-run/need-shell
      sentences. `NotifyPlanTest` — the body keeping its internal spacing, the
      optional `apply` before the title, `post` as the only head, both length
      limits accepted at the boundary and refused one over, the `-S bigtext` and
      tag in the command, and quote/`$`/backtick escaping. `WmPlanTest` — the
      default 300 s and the explicit one, the 30–3600 bounds refused rather than
      clamped, `reset` refusing a SECONDS token, out-of-range size/density,
      **the fuse baseline preferring Physical while the read prefers Override**,
      the deadline arithmetic, an unarmed deadline never expiring, and the expiry
      sentence. `AutowakePlanTest` — the whole
      decision table (eight combinations, including secure refusing even with the
      screen on), both wake receipt shapes for `wake=via=`, and the guard types.
      `StatusPlanTest` — twelve keys, the first eleven unchanged in name and
      position, `shell` last, and `ok`/`down` from the connection flag.
  28. `FeaturesDryRunTest` — the three new dry runs, driven through the real
      module executors with a context that has no phone behind it: `battery
      allow`/`deny`, `notify post` and `wm size …`/`wm density …` (without
      `apply`) each print a line starting `dry-run: ` naming the exact shell
      command they would run, and none of them reaches the shell to do it; the
      same three with `apply` answer `exit=6` naming `shconnect` rather than
      pretending; `wm` read without self-ADB is `exit=6` too; and a refused
      grammar is `exit=2` in all four modules.
  29. The v0.8.1-pad12 regression pins — one per real-device defect, so each can
      only ever come back as a red test:
      **the missing `wm ` prefix** (`WmPlanTest.everyShellCommandStartsWithWm`)
      walks the two read constants, the two reset constants and every parsed
      apply command and asserts each starts with `wm `;
      `theDryRunLineIsTheRealCommandNotAFragment` asserts the whole line
      `dry-run: would run sh: wm size 900x1600`, which is what the phone showed
      was wrong. **`apply` on either side of `notify post`**
      (`NotifyPlanTest.applyIsAlsoAcceptedAtTheEndAndMeansTheSameThing`) asserts
      the two positions yield one identical command, and
      `aTrailingApplyIsOnlyStrippedWhenATitleAndBodyRemain` pins the three
      boundaries that make stripping safe (`post T apply` keeps `apply` as the
      body, a front `apply` does not strip a trailing one, a body containing
      `apply` is untouched). **The three `battery` outcomes**
      (`BatteryPlanTest.anAlreadySatisfiedStateIsReportedAsUnchanged`) assert an
      already-satisfied state says 本来就在/未改动 and never 已加入, and
      `anUnknownBeforeStateKeepsTheOldWording` pins the fallback when the state
      cannot be read. `FeaturesDryRunTest` also drives both `apply` positions
      through the real executor and asserts they answer identically. **The one
      body the trailing form cannot express**
      (`NotifyPlanTest.aBodyWhoseLastWordIsApplyNeedsTheFrontSwitch`) pins the
      deliberate trade: `post 测试 请 apply` is read as the switch (body `请`),
      and `post apply 测试 请 apply` posts the literal body `请 apply`.
  30. The v0.8.2-pad12 regression pins — the read/baseline split, in both directions:
      `WmPlanTest.theCurrentSizePrefersTheOverrideLine` and
      `theCurrentDensityPrefersTheOverrideLine` assert the read command's helpers
      take `Override` first (the P3 defect: a phone set to 900x1600 answered
      1200x2670); `theOriginalSizePrefersThePhysicalLine` and
      `theOriginalDensityPrefersThePhysicalLine` assert the fuse baseline still
      takes `Physical` first; and
      `theFuseBaselineStaysPhysicalWhileTheReadShowsTheOverride` feeds **one** input
      to both, so a future "cleanup" that merges the two helpers back into one turns
      red. `theReadLineKeepsTheOldShapeWhenNothingIsOverridden` pins the legacy
      byte-for-byte line, and `theReadLineSaysSoWhenTheOverrideIsInCharge` pins
      `wm size 900x1600 density 520 (override; physical 1200x2670/520)` including
      the `?` placeholder when the physical value cannot be read.
  31. The v0.8.3-pad12 `z` flag, one case per direction so neither can drift:
      `UiTreeTest.anInvertedRectIsMarkedWithZ` feeds the real 知乎 rect
      `52,254,1148,86` and asserts the line is still emitted with `z` at the end
      (marking, not filtering); `aNormalRectIsNotMarkedWithZ` asserts `0,0,100,50`
      carries no `z`; `aClickableNodeWithAnInvertedRectCarriesCThenZ` asserts a
      clickable node with the same bad rect answers `cz` — `c` first, `z` last —
      which is the pin on the append-only letter order. Every pre-existing
      `UiTreeTest` case is untouched: a node with no rect prints an empty bounds
      column and stays unmarked, so the old byte-for-byte expectations still hold.
      `anEmptyNodeWithAnInvertedRectIsStillSkipped` pins the other half of "mark, do
      not filter": `z` is not a flag that can summon a line for a node with no text,
      no description and no resource id — such a node is skipped exactly as before.
   32. The v0.8.4-pad12 ancestor fallback for a `z` node, one case per branch, all on
       the pure `ClickPlan` (no device, no framework call):
       `theNearestClickableAncestorWinsOverAGrandparent` pins "nearest, not outermost";
       `aClickableAncestorWithoutAUsableRectIsSkipped` feeds a clickable wrapper whose
       own rect is dead and asserts the walk keeps going up (pressing it would only
       move the failure one level); `noClickableAncestorAnywhereKeepsTheOldLine`
       asserts the byte-for-byte v0.8.3 line `node has no usable bounds: 删除 matches=1`
       so the no-ancestor answer cannot drift; `theHintNamesTheAncestorAndTheSwitchThatWouldPressIt`
       pins the hint wording including the `add \`up\` to tap it` tail;
       `upWithNoAncestorNamesTheHitInsteadOfTapping` pins `no clickable ancestor for: …`;
       `upTapsTheAncestorCentre` pins the tap point (the ancestor's centre, not the
       hit's); `aHitWithAUsableRectNeverEntersTheAncestorLogic` is the guard on the
       default path — a normal node inside a clickable row still takes the gesture
       branch even with `up` given, so the new wording can only ever appear for a
       `z` node; and `upIsALeadingSwitchInAnyOrderWithApply` /
       `aSelectorThatStartsWithUpStaysReachableThroughAKindPrefix` /
       `upIsClickOnlyAndDoesNotLeakIntoLongclick` pin the grammar: `click up apply 删除`,
       `click apply up 删除`, `click up` and `click text:up arrow` and
       `longclick up 登录` (still a selector). `aMissingRectIsNotUnusable` keeps `z`'s
       definition — no rect prints an empty column and is not marked — and
       `theSuccessLineKeepsTheDocumentedTailAndAddsTheAncestor` pins
       `clicked via=ancestor node=删除 at=100,150 ancestor=android.widget.LinearLayout 0,100,200,200`.
   33. The v0.8.5-pad12 geometry rule, again on the pure `ClickPlan` and again with the
       **real rects** from the 糖豆 page that forced it:
       `overlapsNeedsBothAxesAndAMissingRectAnswersNo` pins the predicate itself — a
       missing rect on either side answers false, sharing a column is not enough, the
       `z` rect `52,254,1148,86` is normalised before comparing, and
       `120,2590,529,2590` against the 注册 button `1060,402,1153,451` does not overlap;
       `overlapsCountsAnEdgeThatOnlyTouches` pins the inclusive `<=` on the 静音 rect
       `10,2590,36,2590`, which only touches the bottom edge of the bar that holds it;
       `aCrossSemanticAncestorIsNoLongerAHomeForTheTap` is the regression the rule
       exists for — the nearest clickable ancestor is the 注册 button, and the answer
       goes back to the byte-for-byte old line instead of naming it;
       `aZeroWidthNodeStillBelongsToTheWideBarThatHoldsIt` pins both answers for a
       genuine match (the hint wording, and the tap at `600,2563`, the bar's centre);
       `anAncestorWithNoRectOfItsOwnIsNotACandidate` covers the `isUsable(null) == true`
       corner — a clickable wrapper with no rect is still not named;
       `upWithOnlyCrossSemanticAncestorsNamesTheHitInsteadOfTapping` pins `up` with
       nothing overlapping (`no clickable ancestor for: …`, exit 5);
       `theWalkSkipsAnAncestorDrawnElsewhereAndTakesTheOneAbove` pins that the walk
       keeps going past a non-overlapping ancestor and takes the one above it; and
       `aWalkWithoutAHitKeepsTheOldNearestAnswer` pins the `hit == null` form as the
       pre-v0.8.5 nearest answer. The 17 v0.8.4 cases above are untouched and still
       green — every ancestor rect they use already overlaps the hit.
   34. The v0.9.0-pad12 camera page, all of it on the pure `CamPlan` (the Activity
       itself is not unit-tested — Camera2 cannot run on a plain JVM):
       `blobNameIsTheMillisAndTheSuffix` pins `cam-1733456789012-a1b2.jpg`;
       `blobNameLowerCasesAnUpperCaseSuffix`, `blobNameFallsBackWhenTheSuffixIsNotFourHexCharacters`
       (a 3-char and a 5-char suffix both become `0000` — a name the relay would
       reject must never be produced) and `blobNameClampsANegativeClock` cover the
       four ways the name can be built wrong, and `blobNameFitsTheRelayNameWindow`
       keeps it under `ShotBlob.NAME_MAX_CHARS`.
       `fitScaleBringsTheLongestSideToTheLimit` / `fitScaleNeverEnlarges` /
       `fitScaleUsesWhicheverSideIsLonger` / `fitScaleRefusesIllegalNumbers` pin the
       scale ladder — the "never enlarge" case is the one that matters, because a
       device that already offers 1280 must be left byte-for-byte untouched.
       `summaryLineUsesKilobytesAboveOneKiB` (the exact sentence
       `已发送：图 148 KB（1280x720）+ 文字 12 字`), `summaryLineUsesBytesBelowOneKiB`
       (`NNN B`) and `summaryLineSaysZeroCharsWhenNothingWasTyped` pin the one line the
       person reads; `errorLineAlwaysNamesTheFailure` pins `发送失败: <reason>` and its
       未知原因 fallback. `noteBodyCarriesExactlyTheSixFields`,
       `noteBodyEscapesQuotesAndNewlines` and `noteBodyEscapesControlCharacters` pin the
       hand-written JSON — field order, and the escaping the relay's parser needs.
       `clampTextTrimsAndKeepsShortText`, `clampTextCutsAtFiveHundred` and
       `clampTextDoesNotSplitASurrogatePair` pin the 500-code-point cut: the last one
       feeds 500 emoji and asserts the tail is still a whole pair, because cutting on
       a UTF-16 index would send half a character. `pickSizePrefersSixteenByNineNearTheTarget`,
       `pickSizeTakesTheClosestLongSideWhenOnlyFourByThreeIsOffered` and
       `pickSizeReturnsNullWhenNothingIsUsable` pin the `SCALER_STREAM_CONFIGURATION_MAP`
       choice — aspect weighted over long side, and `null` rather than a crash when
       the map offers nothing usable.

   35. The v0.10.0-pad12 `speak` verb, all of it on the pure `SpeakPlan` (the
       network, the `MediaPlayer` and the pipeline thread cannot run on a plain
       JVM). Grammar: `switchesAreOnlyConsumedWhileTheyLead` pins the leading-only
       rule — `speak hi speed=2` keeps `speed=2` in the text —
       `aSentenceThatMerelyContainsSpeedKeepsItVerbatim` the same for a sentence
       that merely mentions a flag, and `innerSpacesOfThePayloadSurvive` verbatim
       text. `unusableLinesAreRefusedRatherThanGuessed` pins the six refusals
       (empty, blank, a bare `speed=1.5`, `voice=`, `voice= hi`, `speed=abc hi`): a
       flag that was meant as a flag must not become a sentence nobody asked for.
       `speedIsClampedToTheServersRange` pins the clamp instead of a refusal
       (`0.1` → `0.50`, `9` → `2.00`) plus the four `speedText` forms
       `1.00`/`0.50`/`2.00`/`1.25`, and
       `theNormalisedArgumentStringKeepsOnlyTheSwitchesThatMatter` pins `normalize`
       and the `usage:` line. `theBakedVoiceIsUsedOnlyWhenTheLineNamesNone` pins
       `voiceOrDefault` in all four shapes, `endpoint` (trailing slash and
       surrounding spaces gone, so `base + "/tts/"` never becomes `//tts/`) and
       `chunkFileName` (`tts-0.mp3`, `tts-12.mp3`).
       Chunking: `theLimitsAreTheMeasuredOnes` pins 40 / 120 / 500 **and their
       order** — first < later < server, which is the point of the ladder;
       `blankTextIsNoChunksAtAll` the empty answer;
       `aShortLineIsOneChunkAndIsNotRewritten` that a line under the limit is
       handed over untouched (including exactly 40);
       `sixtyCharactersBecomeTwoChunksSoTheFirstSoundIsEarly` (40 + 20),
       `aHundredAndTwentyCharactersStayWithinTheLaterLimit` (40 + 80) and
       `twoHundredCharactersBecomeThreeChunks` (`[40, 120, 40]`) the packing
       ladder. `aCutPrefersASentenceEndThenAClauseEndThenTheHardLimit` pins the
       boundary preference, `aLongSentenceIsCutAtCommasRatherThanMidWord` that
       every chunk of a comma text ends at a comma, and
       `aTwoHundredAndFortyCharacterUtteranceStartsWithinFourtyCharacters` pins the
       asymmetry — eight 30-character sentences, first chunk is one whole sentence
       and the rest stay under 120. `aChunkNeverSplitsASurrogatePair` feeds 39 `a`
       + one emoji + 20 `b` and asserts the first chunk stops at 39, because
       cutting on a UTF-16 index would send half a character to the server, and
       `everyChunkIsNonEmptyAndTheTextIsNeverLost` re-joins the chunks and compares
       with the input — the one assertion that catches every off-by-one in the
       cutter. Wire: `theBodyIsExactlyWhatTheServerExpects` pins field order,
       `speed:1.00` and the escaping of a quote, a backslash and a newline in one
       body. Receipts: `aFinishedRunReportsCharsChunksFirstSoundAndBytes` pins the
       exact success sentence, `aFallbackSaysWhyTheFishPathWasAbandoned` the
       fallback sentence plus `NOT_CONFIGURED` and `EMPTY_AUDIO`, and
       `aPartialRunNeverClaimsToHaveReadEverything` the partial sentence that says
       it did **not** fall back. `serverErrorsKeepTheServersOwnWords` pins
       `服务端 502` with no detail, `服务端 401（invalid secret）` with one, the
       `error: detail` join, a non-JSON body kept verbatim, and the 200-char hint
       clipped to one line ending in `…` — a server that answers with a stack trace
       must not become the receipt. `aStuckPlayerIsBoundedByTheDecodedDuration`
       pins the cap (`0` and `-1` → the 120 s fallback, 3000 → 3000 + 5 s) and the
       `8.00s` / `2.40s` / `0.00s` printing; `theTimeoutsAreTheAgreedOnes` pins
       10 s / 90 s and the 2-chunk lookahead bound.

   36. The v0.11.0-pad12 pair — the volume guard and the two camera-state words.
       The camera half is on the pure `CamPlan`:
       `modeBodyOpenCarriesJustTheIdAndTheKind` pins the exact two-field body
       `{"id":"PHONE-MEIZU","kind":"cam-open"}`, `modeBodyCloseCarriesJustTheIdAndTheKind`
       the closing one, `modeBodyRejectsAnyOtherKind` the three near-misses that must
       never be sent (`cam-opening`, `CAM-OPEN`, empty — each becomes
       `"kind":""`), `modeBodyEscapesTheId` the quote-and-newline escaping that the
       body inherits from `noteBody`, `modeNoteAcceptsOnlyTheTwoWords` the guard the
       Activity checks before it starts a thread, and
       `modeBodyMatchesTheWordsTheRelayStores` the literal strings — the relay stores
       `kind` verbatim, so those two words *are* the contract with the operator's
       side. The volume half of v0.11.0 had no JVM test on purpose: `AudioManager`
       only exists on a device, and a plan object that pretends to test a system
       service would pin nothing but its own mock. That half is **gone** in
       v0.11.2-pad12 (case 38); the device check that replaced it is
       manualcheck.md.

   37. The v0.11.1-pad12 audio route, the one thing about `speak` a JVM test *can*
       pin. `speakAsksForTheMediaStream` asserts
       `FeatSpeak.SPEAK_USAGE == AudioAttributes.USAGE_MEDIA` **and** that it is
       not `USAGE_ASSISTANT`. Both halves matter: the first is the fix, the second
       is the regression — v0.11.0 raised `STREAM_MUSIC` while playing on
       `STREAM_ASSISTANT` (max 15, sitting at 11), which is why the owner heard a
       quiet voice on a phone whose media volume was at 60/60.

       `SPEAK_USAGE` is a `const val Int`, not a function returning
       `AudioAttributes`: the test inlines the value, so no `android.media` class is
       loaded and nothing is mocked (`unitTests.isReturnDefaultValues = true` would
       make a call that *returns* a framework constant answer `0`, and an assertion
       of `0 == 0` proves nothing). The `AudioAttributes.Builder` call stays in
       `FeatSpeak.speakAudioAttributes()` because it is device-only. This is the
       first `android.*` import in `app/src/test`, and it is a constant, not a
       service.

   38. The v0.11.2-pad12 deletion — `speak` no longer touches the phone's volume.
       `speakNeverTouchesThePhonesVolume` reads `FeatSpeak.kt` from the module
       directory and asserts the file contains **no** `VolumeGuard`,
       `AudioManager`, `setStreamVolume` or `getStreamVolume`, and still asks for
       `USAGE_MEDIA`. This is the honest way to pin an absence: there is no
       `AudioManager` on a JVM to observe, so what is checked is the source shape
       the absence produces. It is a source read, not a behavioural test — if the
       file ever moves, `Assume.assumeTrue` skips instead of failing, and
       manualcheck.md §"speak 前后音量表读数必须一致" remains the real proof. The
       test that pinned the old behaviour is the comment in
       `speakAsksForTheMediaStream`, which no longer calls the guard "the stream
       VolumeGuard raises" — nothing asserted the guard itself, so nothing had to
       be deleted.

    39. The v0.11.3-pad12 parallel pipeline and the single-request short path. The
        board is its own pure class, so a plain JVM can drive it with a lambda that
        returns a `String` and sleeps: `deliversInIndexOrderEvenWhenTheFirstChunksAreTheSlowest`
        (index 0 is the slowest and still comes out first),
        `everyChunkIsSynthesizedExactlyOnce`, `threeChunksAreSynthesizedAtTheSameTime`
        (a peak counter proves the fan-out really is 3, not one thread with three
        names), `lookAheadIsBoundedByTheCapacity` (capacity 2 — the third chunk is
        not even started while the player idles),
        `aFailureIsDeliveredInItsOwnPositionAndEndsTheRun` and
        `afterAFailureNoLaterChunkIsStarted` (the run stops at the failure instead
        of burning requests nobody will hear),
        `aThrowingChunkIsDeliveredAsACrashInItsOwnPosition` (a synthesizer that
        throws becomes a sentence, not a stack trace on the poll thread),
        `aWedgedSynthesizerIsReportedInsteadOfWaitedOnForever` (`take(5000)` gets
        chunk 0, the next `take(200)` answers `Stalled(index=1)`),
        `stopUnblocksAConsumerThatIsStillWaiting`,
        `stopDoesNotWaitForeverForAWorkerStuckInASocketRead` (a worker parked on a
        latch — `stop()` still returns in under 4 s),
        `aLongRunKeepsOrderWithoutDeadlocking` (40 chunks, capacity 4) and
        `moreWorkersThanChunksIsNotAnError`.
        `parallelSynthesisIsThreeWideAndGivesUpOnAWedgedChunk` pins the constants
        (3 / 4 / 110 s, and `SYNTH_WAIT_MS > READ_TIMEOUT_MS`) plus the two
        sentences a wedged or throwing chunk produces. The short path is pinned on
        the chunker: `theLimitsAreTheMeasuredOnes` now also pins
        `SINGLE_SHOT_MAX == 80`, and
        `aLineUpToEightyCharactersIsOneRequestAndEightyOneIsTwo` is the boundary —
        60 and 80 characters are one chunk, 81 is `[40, 41]`. Three older
        assertions had to move with it: the v0.10.0
        `sixtyCharactersBecomeTwoChunksSoTheFirstSoundIsEarly` (60 characters are
        one chunk now, so that case became the 80/81 boundary above), and both
        `aCutPrefersASentenceEndThenAClauseEndThenTheHardLimit` and
        `aChunkNeverSplitsASurrogatePair` had payloads under 81 characters, which
        no longer produce a cut at all — their inputs were lengthened so the cut
        they pin is still exercised.

    40. The v0.11.4-pad12 camera switch. The switch itself is an Activity concern, so
        what a JVM can pin is the vocabulary around it, on the pure `CamPlan`:
        `openCameraTextIsNotTheCloseLabel` (打开摄像头 is not 关闭摄像头 — one button,
        two labels, and a copy-paste slip would leave the owner tapping the same word
        twice and never understanding why nothing changed),
        `camOffTextPromisesATextOnlySend` (the placeholder says 只发文字, so the grey
        rectangle explains itself instead of looking like a camera that broke),
        `textOnlyLineNamesTheCharacterCount`,
        `textOnlyLineSaysNoPictureInItsOwnWords` (（没带图） in both branches — the
        sentence the owner reads after a text-only send has to say the picture was
        not part of it), `textOnlyLineFallsBackWhenTheCountIsNotUsable` (0 and
        negative counts do not print 文字 0 字) and
        `aTextOnlyNoteHasAnEmptyBlobAndNoSize` (`noteBody(id, text, "", 0, 0)` is
        still six fields, `"blob":""` and `"w":0,"h":0` — the wire shape the relay
        already accepts). What no JVM test can reach is `toggleCamera()` releasing
        and re-opening a real `CameraDevice`; manualcheck.md §"摄像头开关" is that
        proof, and `cam` on the control side is where the operator sees the result.

    41. The v0.11.5-pad12 receipt. The ack thread is an Activity concern (a real
        socket on a real clock), so what a JVM can pin is the vocabulary and the
        promise it makes, on the pure `CamPlan`:
        `uploadedLineNamesTheSizeAndTheCharacterCount` (the same 图 148 KB（1280x720）
        + 文字 12 字 the owner already recognised, with 等我确认 at the end),
        `uploadedLineAsksForConfirmationInsteadOfClaimingItWasSeen` (the pending line
        must not contain 已读 — that is the one sentence in this feature that would be
        a lie the owner repeats to the person in front of him),
        `uploadedLineIsNotTheOldSentSentence` (starts with 已上传, not 已发送: 已发送
        only ever proved the phone reached the relay),
        `uploadedTextOnlyLineNamesTheCharacterCount` /
        `uploadedTextOnlyLineFallsBackWhenTheCountIsNotUsable` (the picture-less send
        keeps 没带图, including for a zero or negative count) and
        `everyPendingLineAsksForConfirmation` (both shapes of the pending line carry
        the same clause, so neither path silently keeps the old reading),
        `readLineIsTheShortAnswer` (已读, two characters, readable across a room),
        `unreadLineSaysTheServerHasItAndNobodyLooked` (服务器收到了，但还没人看 — not an
        error: nothing failed, the note is waiting) and
        `readAndUnreadAreDifferentSentences` (if those two collapse the ack thread has
        no observable output left to test). What no JVM test can reach is the relay
        answering `/agent/note-status` and the 30 s window elapsing;
        manualcheck.md §"回执" is that proof, and `rc.py look` is where the operator
        makes the read happen.

**Design for testability**: keep `Commands.parse`, the size ladder, the tree
formatter's string building, URL joining and JSON building in classes/functions
that take plain data (no `Context`, no `AccessibilityNodeInfo`). Wrap
`AccessibilityNodeInfo` in a tiny `NodeView` interface so the formatter is pure.

---

## 11. Definition of done

- `./gradlew assembleDebug` succeeds on a clean GitHub runner (that is the only
  build we have — there is no local Android SDK).
- `./gradlew test` passes with the §10 tests, **0 failures** (see the count in the
  release commit for the current version).
- `manualcheck.md` documents, for each command, the exact phone-side action that
  proves it works (screenshot of the phone, tap landing on the right target,
  location matching Google Maps, …). CI cannot prove this; a human does.
- `C:/Users/67972/.dsh/skills/phone-agent/SKILL.md` is updated in the same commit:
  new/changed/removed verbs in its command table, changed formats or wire
  protocol, new real-device pitfalls, and its `covers:` / `updated:`
  frontmatter. That file is the **only** manual another agent can read — it
  cannot see this repo. A release whose skill still describes the old version
  is not done (owner rule, 2026-11-28).
