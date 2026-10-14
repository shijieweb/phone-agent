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
| `ScreenShot.kt` | Accessibility screenshot → downscaled JPEG → base64 |
| `LocationHelper.kt` | One-shot fix + periodic reporting |
| `BootReceiver.kt` | Restart the service after boot / app update |

Deliver `AndroidManifest.xml`, `res/values/strings.xml` and
`res/xml/accessibility_service_config.xml` **exactly as already written** — they
exist in this repo; do not rewrite them.

**Out of scope (do not build):** MCP server, HTTP server, WebSocket, OAuth,
camera, file manager, notifications, sharing, app install/uninstall, offline
geocoding, IP database, any AndroidX/Compose/Ktor/Hilt usage, any shell command
execution.

---

## 2. Relay contract (already live — do not change)

Base URL default: `https://dsh.owen1.de5.net:19092`

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
`android=16;model=meizu22;acc=on;bat=87;scr=on`.

---

## 3. Command grammar

One command per `cmd` string, single line, ASCII, no quoting constructs.
Parsing is whitespace-split; **arguments after the command name are positional**.

| Command | Effect |
|---|---|
| `ping` | stdout `pong` |
| `status` | one-line JSON (see below) |
| `shot [maxPx] [quality]` | screenshot, stdout = base64 JPEG (see §4) |
| `tree [maxNodes]` | indented node tree, default `maxNodes=400`, cap 800 |
| `tap X Y` | tap at ints X,Y |
| `long X Y [ms]` | long press, default 600 ms, cap 5000 |
| `swipe X1 Y1 X2 Y2 [ms]` | swipe, default 300 ms, cap 3000 |
| `key NAME` | global action: `back`, `home`, `recents`, `notifications`, `quicksettings` |
| `text STRING` | type `STRING` into the focused field (rest of line, verbatim) |
| `apps` | list launchable apps, one per line: `<package>\t<label>` |
| `app PACKAGE` | launch the app's main activity |
| `loc` | one location fix, stdout = one-line JSON |
| `locloop INTERVAL` | start periodic reporting every `INTERVAL` s (min 30, max 3600) |
| `locstop` | stop periodic reporting |
| `stop` | stop the agent service (result is sent first) |

Anything else: `exit=2`, stderr `unknown command: <name>`.

`status` stdout (one line):
```json
{"ok":true,"ver":"0.1.0","acc":true,"svc":true,"scr":true,"bat":87,"loc":true,"loop":0,"queue":0}
```
`acc` = accessibility service connected, `svc` = agent service running,
`scr` = screen interactive, `loc` = location permission granted,
`loop` = periodic location interval in seconds (0 = off).

### Exit codes
| Code | Meaning |
|---|---|
| 0 | success |
| 2 | bad usage / unknown command |
| 3 | accessibility service not connected |
| 4 | timeout (gesture not completed, location fix timed out) |
| 5 | not found (package, node, focus target) |
| 6 | permission denied |
| 7 | interrupted (service stopping) |

---

## 4. Screenshot encoding (the one tricky part)

The relay carries **text only** and truncates `stdout` at 200 000 chars, so:

- Capture via `takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)`.
- On success: `Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)`,
  then **close the hardware buffer immediately**, then scale down.
- Scale so the **longest side ≤ `maxPx` (default 720, clamp 320..1440)**.
- Encode `Bitmap.CompressFormat.JPEG`, quality `q` (default 55, clamp 20..90).
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

- Relay base URL (`EditText`, default `https://dsh.owen1.de5.net:19092`)
- Agent id (`EditText`, default `PHONE-MEIZU`)
- Secret (`EditText`, `inputType=textPassword`)
- `保存` button → persists to SharedPreferences
- `启动 / 停止` button → starts/stops `RcAgentService`
- `请求权限` button → requests `POST_NOTIFICATIONS`,
  `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, then (second pass)
  `ACCESS_BACKGROUND_LOCATION`
- `打开无障碍设置` button → `ACTION_ACCESSIBILITY_SETTINGS`
- `打开电池优化设置` button → `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
- A `TextView` status line refreshed every 2 s from a static snapshot held by
  `RcAgentService` (service state, accessibility on/off, last poll time, last
  error). Never do network I/O on the UI thread.

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
   line verbatim including spaces, unknown command → exit 2.
2. Screenshot size guard: given a byte count, the re-encode ladder picks the
   expected step and reports it.
3. Node-tree formatting: given a small fake node model, the emitted lines match
   the format in §5 (truncation, `|` escaping, flag letters, depth).
4. Relay URL joining: base URL with/without a trailing slash produces exactly
   `/agent/hello` etc. (no double slash).
5. Location JSON serialisation field names and rounding.

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
