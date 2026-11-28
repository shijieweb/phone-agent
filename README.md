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

The app has four screens: the config page, a 视频转音乐 page one button below
it (v0.7.0-pad12), a 摄像头看图 page next to that one (v0.9.0-pad12), and — hidden
behind five presses on the version line at the bottom — an operator's debug page
that runs one command line through the live agent and shows the answer, same
`Commands.parse` path as the relay, no round trip. See `SPEC.md` §6.1, §6.2 and
§3.29.

Since v0.5.8-pad12 the config page is written for the phone's owner, not for me:
one big 运行中 / 已停止 word, one 启动 button, one 屏幕常亮 switch, and a single
一键设置权限 button that does the next missing thing instead of asking which of
four buttons to press. URL / secret moved into a collapsed 高级设置 section.

Logs are capped in all three places they accumulate — the in-memory ring (400
lines), the watchdog log on `/data/local/tmp` (64 KB, trimmed by the script
itself before each beat) and cached APKs (24 h). `logs size` prints the three
numbers, `logs trim` shows what it would delete, `logs trim apply` does it.
See `SPEC.md` §3.18.

Since v0.6.0-pad12 `tree` reads the active window through
`rootInActiveWindow` and merges it with the other windows (IME, dialogs) instead
of trusting `AccessibilityWindowInfo.getRoot()`, which on Flyme / Android 16
returns a cached snapshot: a panel that expanded inside the window already on
screen stayed invisible to `tree` — and therefore to `click apply` — until the
window was re-activated. The screen is also read twice, 150 ms apart, and the
later answer wins when the two disagree. See `SPEC.md` §5.

Since v0.6.2-pad12 the config page answers one question — 现在到底好不好 — with
one lamp and one word: green 运行中, amber 部分异常, red 已停止, plus three small
lamps for 无障碍 / 电池 / 自连ADB. The line under it names only what is wrong, in
a fixed order, and the verdict is red the moment the service stops no matter how
green the rest is. All four facts are re-probed every 3 s while the page is in
front (never cached, never on a background thread, never any network). See
`SPEC.md` §6.

Since v0.6.5-pad12 pressing 停止 really does silence the phone, on cellular too.
The guard script could only be stopped through self-ADB, and on mobile data there is
no self-ADB, so the guard kept reviving the app every 60 s and the screen popped up in
front of whatever the owner was doing. The app now writes its own stop marker to
`/sdcard/.phoneagent-stop` — a place both the app and the uid-2000 shell can reach —
and the script exits on its next beat when it sees it. When the guard does revive the
agent it now asks for the **service** (`am start-foreground-service`), which builds no
UI, and only falls back to the activity if that is refused. `watchdog arm` clears the
marker before probing, so an old stop cannot make a fresh guard exit immediately. See
`SPEC.md` §3.17.

Since v0.7.0-pad12 the phone can turn a video's soundtrack into an MP3 **by
itself**, with no PC and no ffmpeg: `MediaExtractor` + `MediaCodec` decode the
audio track and a vendored LAME port (jump3r, pure Java, ~350 KB of source)
encodes it at 128 kbps / 44100 Hz. Put the videos in `/sdcard/视频转音乐专用`,
press 视频转音乐 on the config page and then 开始转换, and each `<name>.mp3`
lands next to its video; files without an audio track are counted and skipped,
and an existing MP3 is left alone. From the relay it is `mp3 scan` (read-only
list) and `mp3 run` / `mp3 run apply [force]`. Measured: **45× realtime**
for the encoder alone (221 ms per 10 s of audio) — a 3-minute video takes a few
seconds, and the decoder, not LAME, is what you wait for. ffmpeg was rejected on purpose:
`targetSdk 34` forbids executing a binary from the app's private directory, and
shipping the 20 MB static build is not worth it. See `SPEC.md` §3.20.

v0.7.1-pad12 fixes the reason the first MP3s were **silent**. They were valid
files — right length, right bitrate, decodable — at 1/65536 of full scale,
because `lame_encode_buffer_int` wants 16-bit samples shifted left by 16 bits and
v0.7.0 handed it the raw values. `ffmpeg -af volumedetect` measured
-91 dB before and -4.3 dB after. Byte counts and frame sync could never have
caught it; a silent MP3 is a perfectly well-formed MP3.

v0.7.2-pad12 fixes two ways an ordinary phone video could be misjudged, both of
them because the app trusted the **track header** instead of the **decoder**.
An HE-AAC track declares 22050 Hz while the decoder's SBR doubles it to 44100, so
the app built its encoder on the wrong rate and then failed the file with
解码器改变了格式 — a good video rejected. And a track that declares float while
the decoder emits 16-bit shorts (a real downgrade, androidx/media issue #565) was
read as float, i.e. as noise. Rate, channel count and sample format now all come
from `codec.outputFormat`, and the encoder is created lazily at the first block of
PCM so it can. Surround sound (more than two decoder channels) is now **refused
with a sentence that names the channel count** instead of being fed to a stereo
encoder, which would have shifted every sample into screech. The per-file timeout
went from 10 to 30 minutes (7× realtime on the tablet covers a 3.5-hour
recording); a timed-out file may still finish in the background, and the failure
line says so. See `SPEC.md` §3.20.

v0.7.3-pad12 fixes what the owner hit first in real use: she opened 视频转音乐,
went off to copy a video into the folder with the file manager, came back — and the
page still showed the count from when she arrived (没有要转的, button disabled).
Coming back only *resumes* the Activity, and the count used to hang off `onCreate`.
It now hangs off `onResume`, so returning to the page re-counts by itself, and a
刷新 button next to 开始转换 is the manual fallback. Both go through the same
guarded `refresh()`: one scan at a time, and none at all while a batch is running,
when the numbers on disk are still moving. See `SPEC.md` §6.2.

v0.8.3-pad12 makes the node tree say which nodes **cannot be tapped**. On the real
phone a large share of the accessibility rects come back inverted or zero-area —
`52,254,1148,86` (bottom above top) in 知乎, 58 of 72 text nodes on one 糖豆 page,
97 of 163 on a 百度 page on the tablet — and `click` on one of them has no
coordinates to fall back on, so it answers `node has no usable bounds` unless the
node's own `ACTION_CLICK` lands — and nothing in the `tree` output said which was
which in advance.

`flags` now ends with `z` for exactly those nodes, appended **last** so every older
letter keeps its position and existing parsers keep working. They are marked, not
filtered: their text is still evidence of what is on the screen, and on a busy page
they are the majority of the text nodes, so dropping the lines would throw most of
the content away. `z` is not a flag that can summon a line of its own — an empty
container with a bad rect is still skipped. See `SPEC.md` §5 and §10 case 31.

v0.8.4-pad12 is what that `z` letter was for. A node with a dead rect has no
coordinates, so `click` on it could only ever answer `node has no usable bounds` —
while the clickable row it sits in was right there, one line up in the tree, and the
operator had no way to say "press that instead". `click` now walks up: when the
matched node's own rect is unusable and `ACTION_CLICK` already failed, it looks for
the nearest ancestor that is both clickable and has a usable rect. Finding one, the
answer still fails — but it now *names* it: `node has no usable bounds: <node>;
nearest clickable ancestor is <class> "<text>" at <l,t,r,b> — add \`up\` to tap it`.
Adding the switch (`click up 删除`) taps that ancestor's centre and prints
`clicked via=ancestor … ancestor=<class> <l,t,r,b>`.

The tap is opt-in because walking up crosses a semantic boundary. The 「删除」 label
inside a row belongs to a different control than the row: tapping the row opens the
detail page, it does not delete. The row is the only thing with a rect, so pressing
it is a guess about which control the label meant, and a wrong guess on a phone
nobody is holding is destructive. Nothing else about `click` moved: a node with a
centre of its own — including the zero-area text nodes that are most of a busy page
— takes exactly the gesture path it always took. See `SPEC.md` §3.1 and §10 case 32.

v0.8.5-pad12 makes that walk up **land somewhere it belongs**. Walking up alone
turned out to be a trap on a real page: measured on a 糖豆 page (1200x2670), all
113 text nodes with a `z` rect reached the *same* off-screen 注册 `Button` at
`1060,402,1153,451` — the WebView had pushed it onto the same line — so v0.8.4 would
have named that button as "the nearest clickable ancestor" for 113 different labels,
and 25 of them would have tapped it. A candidate ancestor must now **overlap the
node's rect on both axes** (`aLeft <= bRight && bLeft <= aRight && aTop <= bBottom &&
bTop <= aBottom`, rects normalised first, missing rect is never an overlap). A
parent normally contains its child, so the ancestor that really owns a label always
shares pixels with it; the cross-semantic one does not. The count drops to 27 of 113
and every one of the 27 lands on the full-width player bar `0,2536,1201,2590` that
really does hold the label. Nothing else moved: no overlapping ancestor → the
byte-for-byte old line (no hint), `up` with nothing overlapping → the same
`no clickable ancestor for: …` exit 5, and a node with a centre of its own never
enters this code at all. See `SPEC.md` §3.1, §5 and §10 case 33.

v0.10.0-pad12 is a second voice, and a decision about where it lives. `say` uses
the phone's own engine: always there, no network, and it sounds like a phone
reading a menu. `speak` sends the sentence to the Fish TTS service on the VPS
instead — `POST {ttsBase}/tts/` with `X-TTS-Secret`, one MP3 back — and plays it
with a `MediaPlayer`. It is a new verb rather than a change to `say` because the
two failures are not the same failure: `say` fails when the engine has no voice
pack, `speak` fails when a server is busy, and the operator needs to know which.
So `speak` falls back to `say` when the server path cannot be used at all, and
says so in the receipt — `speak 已朗读 24 字（回落系统 TTS：服务端 502）` —
because a sentence already asked for must not be lost to a 502.

Chunking is where the shape comes from. Synthesis costs roughly 0.10 s per
character, so the server's own 500-char cap would be about a minute of silence
before the first sound. Text is cut into chunks instead — first at most 40 chars,
the rest at most 120, preferring a sentence end, then a clause end, then a hard
cut that never splits a surrogate pair — and a daemon thread synthesizes the next
one while the current one plays, through a two-slot queue, so a paragraph never
buffers a hundred MP3s in `cacheDir`. The first chunk is short because
time-to-first-sound is what the person holding the phone judges; the rest are long
because every extra request is a round trip.

A partial run is **not** retried. If audio was already heard, replaying the
paragraph to cover the last sentence would say the opening twice, so it answers
`speak 已播 2/3 块，第 3 块失败：…，未回落（避免重复朗读）` with exit 6. Playback
is capped at the decoded duration + 5 s (120 s when unknown) and polled every
50 ms on the poll thread — no `setOnCompletionListener`, because that needs a
`Looper` this thread does not have, and a player that never reports `false` would
hang the one loop every command shares. The secret is not in the repo:
`phoneagent.ttsBase` / `ttsSecret` / `ttsVoice` live in `local.properties` and are
baked into `BuildConfig.DEFAULT_TTS_*`, the same mechanism as the relay values.
See `SPEC.md` §3.30 and §10 case 35.

v0.9.0-pad12 is the one thing the whole screenshot pipeline cannot do: show a
camera. Every other way this app sees the screen goes through the accessibility
tree or `screencap`, and a camera preview is composited by the HAL into a
hardware overlay that may or may not reach the framebuffer — on this phone a
screenshot does contain the preview, at half resolution with the buttons and the
keyboard in it; on the next one it may be a black rectangle. Either way it is a
picture of a screen, not of the thing being pointed at. So 摄像头看图 opens the
camera itself: a live preview, a box to type 哪里不对 in, and one 发送 button that
captures a still, shrinks it to 1280 px at quality 80, uploads it as
`cam-<millis>-<hex>.jpg` and posts one note (`kind=look`) so the far end knows a
person is asking to be looked at. The preview and the JPEG share one
`CaptureSession` — a still is a request on the session that adds the JPEG target,
so the frame handed back is the frame that was on screen — and the page builds
its own `RcClient` call by call, so it works with the agent stopped. The page is
pinned portrait (v0.9.1-pad12): it is a page for pointing an upright phone at a
shelf, and a rotation would recreate the activity and drop the still in flight.
See `SPEC.md` §3.29, §6.3 and §10 case 34.

v0.8.2-pad12 is one real-device defect and one sentence of documentation. Both
came from the same habit: a helper that answers two questions gets trusted for
the one it was not written for.

**`wm` reported the wrong number once an override was in force.** `wm size`
prints `Physical size:` always and `Override size:` only after a change, and the
device really uses the override. v0.8.1 read the physical line for *both* the
read command and the fuse baseline, so a phone just set to 900x1600 answered
`wm size 1200x2670 density 520` — a successful change that looked like a failed
one. There are now two helpers per field, because there are two questions:
`currentSize`/`currentDensity` (override first) answer "what is in force" for
the read, `originalSize`/`originalDensity` (physical first) answer "what will
reset return to" for the fuse. With nothing overridden the read line is byte
for byte the old format, so existing parsers keep working; with an override it
leads with the value in force and keeps the baseline as an annotation:
`wm size 900x1600 density 520 (override; physical 1200x2670/520)`.

**Documented, not changed: `autowake` cannot light a password-locked screen.**
`AutowakePlan.decide` checks `secure` before `screenOn`, and a locked phone
reports `secure` whether it is dark or lit — so on such a device the only thing
autowake buys is an honest `exit=6 锁屏有密码` instead of the old misleading
`没有出现含…的节点`. That is the intended behaviour; the note is in `SPEC.md`
§3.27 and `manualcheck.md` so nobody later reads a dark screen as a broken
feature.

v0.8.1-pad12 fixes what the first real-device run of v0.8.0 found. Three things,
all of them the kind a unit test passes while the phone disagrees.

**`wm` never reached the phone.** `WmPlan.command` built the apply command as
`kind + " " + value`, dropping the `wm ` prefix, so `wm size 900x1600 apply`
executed `size 900x1600` and answered `/system/bin/sh: size: inaccessible or not
found` with exit 6. Read and both resets were fine because their constants carry
the prefix — which is exactly why nothing caught it. The prefix is now built in
one place, and `WmPlanTest` asserts that the read constants, the reset constants
**and** every parsed apply command all start with `wm `, plus the whole dry-run
line `dry-run: would run sh: wm size 900x1600`.

**`notify post` took `apply` on one side only.** `wm size 900x1600 apply` puts it
at the end, `notify post apply T B` at the front; the same app with two rules got
the accident it invited — a real run posted a notification whose body read
「测试正文 apply」. `apply` is now accepted on either side and the two forms build
one identical command. Stripping is deliberately conservative: a trailing `apply`
is removed only when a title *and* a body remain, a front `apply` never strips a
second one, and a body that merely contains the word is untouched.

**`battery` could not tell "already done" from "just did it".** On a phone that
was already whitelisted, `battery allow apply` still said 已加入 — so the receipt
carried no information about whether anything happened. The state is now read
before the command runs and the answer is one of three: 本来就在白名单里（未改动）,
已加入电池优化白名单, or 已移出电池优化白名单. If the read fails the command still
runs and falls back to the old wording rather than blocking a real change.

Also corrected: `SPEC.md` §3.23 called `shell` the eleventh `status` key. It is
the twelfth — v0.5.5 shipped ten and v0.5.5's `guard` was the eleventh.
v0.8.0-pad12 is the round that made the next ten rounds cheap. Every feature now
lives in its own `Feat<Name>.kt` holding its verb, its parser, its executor and all
of its user-visible text, and `FeatureRegistry` lists them one per line; the only
edits `Commands.kt` and `Executor.kt` needed were a single fall-through branch
each. Feature number thirteen is therefore **one new file plus one line**, and a
test asserts the verbs stay unique and lowercase so a collision fails the build
rather than quietly shadowing an old command. See `SPEC.md` §3.28.

Seven features arrived with the foundation. `where` answers "which page is in
front?" in one line of JSON, which usually removes one `tree` round trip.
`status` grows a twelfth key, `shell`, so a controller can tell a permission
problem from a disconnected self-ADB without running anything else. `media`
sends the five transport keys (play/pause/next/prev/stop) through
`input keyevent`, falling back to `AudioManager` and saying so when self-ADB is
down, because that fallback is not guaranteed. `battery` reads and writes the
optimisation whitelist. `notify post` puts a sentence in the shade — spelled
`notify`, not `notif`, which already lists notifications. `wm` reads and
changes display size and density **with a fuse**: the original values and a
deadline go into `Prefs`, and `CommandExecutor` checks that deadline before every
command, so a resolution the phone cannot drive heals itself within five minutes
instead of needing a factory reset. And `autowake` wakes the screen before the
seven screen-touching commands — and refuses outright, with exit 6, when the lock
screen has a password, because running anyway produces `no node found` and sends
the operator hunting for a selector bug that is not there. See `SPEC.md`
§3.21–§3.27.

Since v0.6.4-pad12 discovery only trusts **our own** advertisement. mDNS is shared:
with the phone and the tablet on the same Wi-Fi, the tablet's wireless-debugging
port was reported as `40157` — the *phone's* port — so `shconnect` spent 30 s
dialling a neighbour and gave up with 连接超时. Every candidate now passes two gates
(its address must be this device's wlan IP; if the address cannot decide, the port
must actually answer on `127.0.0.1`), discovery keeps collecting until the budget
runs out instead of taking the first hit, and when nothing passes the reply says how
many foreign advertisements were dropped. No port is ever "tried anyway". The same
round removed the `RSAPrivateCrtKey` cast from key loading: on Android 12 the platform
hands back a plain `RSAPrivateKey`, so `shkey import` failed with 不是 RSA CRT 私钥
while the very same key imported fine on Android 16. Modulus and exponent are now read
from the PKCS#8 DER when the CRT interface is missing. See `SPEC.md` §3.4 and §3.16.

Since v0.6.3-pad12 **nothing starts the agent by itself.** The owner asked for
开机不自启, so `BootReceiver` now only repairs the phone (stay-on timeout,
accessibility binding, wireless-ADB port) and never calls
`startForegroundService`; `BootPlan.steps` has exactly three steps and a test
asserts a plain boot returns only the ADB-port discovery. The two ways back are
the 启动 button and opening the app (still gated on `auto_start`, so a phone you
silenced stays silent). The 开机自动启动 checkbox is gone — it only ever mirrored
what the button had already decided. Two guard repairs came out of the same
round: `disarm` uses `pkill -9` three times (`SIGTERM` could not kill the
`sleep 60` loop, so it reported a live pid that was already dead), and the
script now carries `# ver=…` on line 2 so `arm` replaces a stale copy instead
of answering 未重复启动 forever. See `SPEC.md` §3.17 and §7.

Since v0.6.1-pad12 the accessibility service also subscribes to content-change
events (collected, never acted on), which is what keeps that cache fresh; and
`tree uia` reads the same tree as **uid 2000** through `uiautomator` for the
times it goes stale anyway — same line format, needs `shconnect`. The dump is
left at `/data/local/tmp/phoneagent-tree.xml` as evidence. See `SPEC.md` §5.1.

| Area | Commands |
|---|---|
| Screen | `shot [b64] [maxPx] [quality]` (screenshot, no MediaProjection prompt — uploaded as a blob and answered with one line of JSON; `b64` forces the old base64-on-stdout output), `tree` (node tree), `status` |
| Touch | `tap`, `long`, `swipe` |
| Keys | `key back\|home\|recents\|notifications\|quicksettings` |
| Text | `text <string>` |
| Apps | `apps`, `app <package>` |
| Location | `loc`, `locloop <seconds>`, `locstop` |
| Elements | `click [apply] [up] SEL`, `longclick [apply] SEL`, `scroll`, `ime enter`, `confirm [apply] [t=N] <text>` — **dry run by default**: they report the node they *would* press; add `apply` to really press it, and `up` to let a node with no usable rect be pressed through its clickable ancestor |
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
| Self-start | only two doors, and neither is the boot: opening the app starts the agent again (unless you pressed 停止), and a `uid 2000` guard script in `/data/local/tmp` revives it after `am force-stop`. A reboot never starts it — press 启动. `watchdog [status\|arm\|disarm]` drives the guard by hand |
| Video → MP3 | `mp3 scan`, `mp3 run [apply] [force]` — decodes each video's audio track on the phone and writes `<name>.mp3` next to it (128 kbps / 44100 Hz, default folder `/sdcard/视频转音乐专用`); `run` is a dry run until `apply`; no ffmpeg, no PC |
| Where | `where` — one line of JSON naming the page in front: `{"ok":true,"pkg":…,"act":…,"scr":"on","kg":"none"}`. The package comes from accessibility; the activity name needs self-ADB and is `""` without it. Ask this before `tree` and you usually need one tree instead of two |
| Media | `media play\|pause\|next\|prev\|stop` — transport keys as `input keyevent` (126/127/87/88/86) over self-ADB; without it the command falls back to `AudioManager` and says so, because that path is not guaranteed to work |
| Battery | `battery` (whitelisted or not), `battery allow [apply]`, `battery deny [apply]` — the optimisation whitelist through `cmd deviceidle whitelist`; a dry run until `apply`, and the receipt separates 本来就在（未改动） from 已加入/已移出 |
| Notify | `notify post [apply] TITLE BODY` — puts a sentence in the phone's notification shade (`cmd notification post`); dry run until `apply`, and `apply` is accepted at either end. Not `notif`, which lists existing notifications |
| Display | `wm` (read), `wm size WxH [SECONDS] apply`, `wm size reset apply`, `wm density N [SECONDS] apply`, `wm density reset apply` — every command really starts with `wm `, the read reports the value **in force** (an override shows as `(override; physical …)`), and **with a fuse**: a changed resolution is restored automatically when the deadline passes (default 5 min), and the next receipt says so |
| Wake gate | `autowake [on\|off]` — when on, the seven screen-touching commands wake the phone first, and a password-locked screen is refused with exit 6 instead of being reported as `no node found` |
| Lifecycle | `ping`, `stop` |

`stop` is authoritative, and it is the same rule from both doors: pressing 停止 in
the app and sending `stop` over the relay both clear "start on boot" before the
service is touched, so the guard is disarmed and the agent cannot come back by
itself after a reboot, an update, a `force-stop` or somebody opening the UI.
Re-arm it with the `启动` button — since v0.6.3-pad12 that is the only way, and
it is deliberate: the owner wanted 开机不自启.

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

Four rules keep it honest: `arm` probes `pgrep -f phoneagent-guard.sh` first so a
5-minute re-arm never stacks copies, and replaces the script when its `# ver=` stamp
does not match the running build; `arm` is skipped with one log line
(`守护未启用：自连 ADB 未连接`) when self-ADB is down, instead of logging a
refusal every five minutes; and pressing 停止 writes the marker **and** kills the
guard with `pkill -9` (three tries, 0.5 s apart — `SIGTERM` waits out the
`sleep 60` and reports a pid that is already gone), so "stop" still means stop — a
killed guard would otherwise be resurrected by the next heartbeat and the service
would return two minutes later.

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
pairing implementation (LGPL-3.0), the Ed25519/X25519 curve code (CC0-1.0) and,
since v0.7.0-pad12, the jump3r LAME port (LGPL-2.1-or-later) that encodes the
MP3s — copied into `app/src/main/java/` and compiled in, not pulled as a
dependency.
Every vendored file keeps its original SPDX header, and the full attribution with
the exact upstream commits is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
Nothing is fetched at build time: no Maven artifact, no JitPack, no git
submodule. That is deliberate — a dependency that updates itself is a dependency
nobody reviewed.

The architecture and several accessibility techniques were learned from
[`danielealbano/android-remote-control-mcp`](https://github.com/danielealbano/android-remote-control-mcp)
(MIT), which is a far more feature-complete project — worth a look if you want
MCP instead of this minimal channel.
