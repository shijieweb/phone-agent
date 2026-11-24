# Manual check — phone-agent on a real device

Everything in this file has to be done **by a human on the phone**, because it
depends on the accessibility service, the system permission dialogs and the
Flyme battery manager. The unit tests only cover the pure logic.

The control side is `scripts\rc-ctl.ps1` in `D:\aiagent\projects\vpsmanage`:

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\aiagent\projects\vpsmanage\scripts\rc-ctl.ps1 -Action List
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\aiagent\projects\vpsmanage\scripts\rc-ctl.ps1 -Action Run -Id PHONE-MEIZU -Cmd "ping" -Wait 30
```

> Never paste the relay secret into this repository. It is a public repo.

> The relay base url is `https://dsh.2-c.cc:19092`. Three addresses this app used
> to ship as its default are **dead on this carrier** and must not be typed back
> in: the direct `https://dsh.owen1.de5.net:19092` (up to v0.2.0), the
> Cloudflare-fronted `https://mcp.owen1.de5.net` (v0.3.0–v0.6.0), and the
> Cloudflare detour `https://relay.2-c.cc` (v0.6.1, retired as a preference, not
> because it was blocked). On this carrier a forged RST is injected into the
> first TLS flight of the two `de5.net` names, so the app can never complete a
> handshake there. Probe 10 proved the blocking follows the *name*, not the IP or
> port: an unrelated SNI on the same IP:port handshakes fine. Upgrading migrates
> a stored copy of any retired default automatically — you only need to type the
> new value if you had entered some other address by hand.

## 0. Values to type into the app

| Field | Value |
| --- | --- |
| Relay base url | `https://dsh.2-c.cc:19092` (direct; see the note above) |
| Agent id | `PHONE-MEIZU` |
| Secret | the contents of `/etc/rc-agent/secret` on the VPS (64 hex chars) — ask the operator, do **not** commit it |

## 1. Install

1. Download `phone-agent.apk` from
   `https://dsh.2-c.cc:19092/d/bb9d05ab128a85d9e3057ef61f136e9f/phone-agent.apk` (no auth).
   *(The old `https://dsh.owen1.de5.net:19092/dl/phone-agent.apk` needed basic auth and was
   retired server-side on 2026-10-17: `de5.net` is a free shared domain that the operator's
   carrier resets by TLS SNI.)*
2. Allow installing from this source, then install. The APK is signed with a
   debug key, so Android will show the usual "unknown developer" warning.
3. Open the app.

## 2. Grant permissions (in this order)

| # | Permission | Where | Expected |
| --- | --- | --- | --- |
| 1 | Notifications | "请求权限" button | system dialog appears, tap Allow |
| 2 | Location — while using | same button | appears after notifications |
| 3 | Location — all the time | same button | second dialog; **must** pick "始终允许" |
| 4 | Accessibility | "打开无障碍设置" button | find "phone-agent", enable it → status line shows `无障碍: 已连接` |
| 5 | Battery unrestricted | "打开电池优化设置" button | pick "不优化" / "无限制" |
| 6 | Screen stay-on (optional) | `屏幕常亮` checkbox | ticks itself only if the write landed; if it springs back, step 2.6 below is missing |
| 7 | v0.4.0 device surfaces | one USB session, see 2.5–2.7 | `admin status` prints `扩展权限:` and says which of them are on |

### 2.5 The three doors that `pm grant` cannot open

Each of these is a *different* kind of permission; run the one line and confirm
the state flips in `admin status` (the `扩展权限:` block, SPEC.md §3.10).

| Surface | Command | Expect |
| --- | --- | --- |
| `files` | `adb shell appops set net.de5.phoneagent MANAGE_EXTERNAL_STORAGE allow` | `所有文件访问=已开启`; `files ls` lists `/sdcard` |
| `notif` | `adb shell cmd notification allow_listener net.de5.phoneagent/net.de5.phoneagent.NotifListenerService` | `通知使用权=已开启`; `notif` lists live notifications |
| `anim`, `screen` | `adb shell pm grant net.de5.phoneagent android.permission.WRITE_SECURE_SETTINGS` | `WRITE_SECURE_SETTINGS=已授予`; `screen stayon on` succeeds |

### 2.6 The ordinary runtime grants (one line, all of them)

```
adb shell "pm grant net.de5.phoneagent android.permission.READ_SMS; pm grant net.de5.phoneagent android.permission.SEND_SMS; pm grant net.de5.phoneagent android.permission.READ_PHONE_STATE; pm grant net.de5.phoneagent android.permission.READ_CALL_LOG; pm grant net.de5.phoneagent android.permission.CALL_PHONE; pm grant net.de5.phoneagent android.permission.ANSWER_PHONE_CALLS; pm grant net.de5.phoneagent android.permission.READ_CONTACTS; pm grant net.de5.phoneagent android.permission.RECORD_AUDIO; pm grant net.de5.phoneagent android.permission.READ_CALENDAR"
```

### 2.7 What no grant can buy

`call` can dial, answer, hang up and switch the speaker, but it cannot carry the
call audio: that path runs through the modem and the audio HAL, and reading it
back needs `CAPTURE_AUDIO_OUTPUT` (signature-level). `RECORD_AUDIO` records the
microphone, which is **not** a call recording. `SET_ANIMATION_SCALE` and
`MODIFY_PHONE_STATE` are `signature|privileged`; they are declared only for
completeness and no command depends on them.

On a sideloaded app Android may refuse step 4 with *"受限设置"*. If that
happens, connect USB once and run:

```
adb shell "appops set net.de5.phoneagent ACCESS_RESTRICTED_SETTINGS allow; pm grant net.de5.phoneagent android.permission.WRITE_SECURE_SETTINGS"
```

## 3. Flyme keep-alive (three switches)

| # | Path | Set to |
| --- | --- | --- |
| 1 | 手机管家 → 权限管理 → 自启动 | allow phone-agent |
| 2 | 手机管家 → 权限管理 → 后台管理 → phone-agent | 允许后台运行 |
| 3 | 设置 → 电池 → 省电模式 / 应用耗电管理 | phone-agent = 无限制 / 允许后台活动 |

## 4. Start the agent

1. Fill in the three values from section 0 and tap **保存**.
2. Tap **启动**. The status line should switch to `服务: 运行中` and within
   ~60 s show a fresh `最近心跳`.
3. From the PC run `-Action List`. `PHONE-MEIZU` must show `online: True` and
   an `age_sec` that keeps resetting.

## 5. Per-command check

Run each command with `-Action Run -Id PHONE-MEIZU -Cmd "<cmd>" -Wait 30`
and compare against the expectation below.

| Command | Expectation |
| --- | --- |
| `ping` | exit 0, stdout is exactly `pong` |
| `status` | exit 0, stdout is a JSON object: `{"ok":true,"ver":…,"acc":<bool>,"svc":<bool>,"scr":<bool>,"bat":<0-100>,"loc":<bool>,"loop":<seconds>,"queue":0}`. `acc` must be `true` after step 2.4. |
| `shot` | exit 0, stdout is **one line of JSON** `{"name":"shot-<millis>-<6 chars>","bytes":<n>,"w":<w>,"h":<h>,"q":<q>,"method":"screen"}`; stderr `w=… h=… bytes=… blob=shot-…`. Fetch the bytes with `GET <relay>/ctl/blob/<name>` and confirm the picture is the current screen. If the upload fails the command must still work: stdout becomes base64 and stderr gains `blob upload failed: <reason>，已回退 base64` |
| `shot 320 30` | exit 0, stderr shows a smaller `w=`/`h=` |
| `shot b64` | exit 0, stdout is one long base64 line (no newlines, no `data:` prefix); stderr `w=… h=… bytes=… b64=…` — this is the only path that still puts the image on stdout |
| `tree` | exit 0, one line per node, each `<depth>\|<class>\|<text>\|<desc>\|<resId>\|<bounds>\|<flags>`. Open the app drawer and re-run — the lines must change. |
| `tap X Y` | exit 0 with stdout `tap X Y`; the thing at those coordinates reacts (verify with `shot` before/after). If the gesture never completes you get exit 4 `gesture did not complete`. |
| `long X Y 800` | exit 0 with stdout `long X Y 800`; a long-press menu opens |
| `swipe 540 1600 540 600 300` | exit 0 with stdout echoing all five numbers; the screen scrolls up one page |
| `key back` | exit 0 with stdout `key back`; goes back |
| `key home` | goes to the launcher |
| `key recents` | opens the recents list |
| `key notifications` | pulls down the notification shade |
| `key quicksettings` | expands quick settings |
| `click SEL` | exit 0, stdout `dry-run: would click "<SEL>" via=action\|ancestor\|gesture pkg=<pkg> node=<label> at=<x>,<y>` and stderr `matches=<n>` — **nothing is tapped**. Watch the screen: it must not change. `via=action`/`via=ancestor`/`via=gesture` name the path `apply` would take. Re-run with `click id:<resourceId>` and `click desc:<content-desc>` to exercise the kind prefixes. |
| `click apply SEL` | exit 0, stdout `clicked via=action\|ancestor\|gesture node=… at=<x>,<y>`, and that exact control reacts. `via=action` means the accessibility action landed on the node, `via=ancestor` means the nearest clickable parent was used (e.g. clicking the text inside a list row), `via=gesture` means it fell back to a coordinate tap. |
| `longclick SEL` | exit 0, `dry-run: would longclick …`, nothing happens |
| `longclick apply SEL` | exit 0, stdout `longclicked via=… node=… at=…`; a long-press menu or context menu opens on that element |
| `scroll down [n] [SEL]` | exit 0, stdout `scrolled down x<n> node=…`; the list/dialog scrolls one step per `n` (default 1, max 20). `scroll down 3` on a long list must visibly advance three steps. `scroll up` returns |
| `scroll down 2 id:feed` | scoped form: only the subtree matching the selector is scrolled, stdout names that container |
| `ime enter` | focus a text field first (e.g. `tap` the search box), then run it: exit 0, stdout `ime enter`; the keyboard's Go/Search action fires (the form submits / the search runs). With no editable field focused: exit 5 `no editable text field` |
| `wake` | with the screen already on: exit 0, stdout `wake already-on scr=on kg=none`. Then lock the phone (screen off) and run it: exit 0 with `wake ok via=wakelock scr=on kg=none\|locked` (or `via=activity`). If the ROM blocks background activity starts you will get exit 4 `wake failed via=none scr=off kg=…` — that is a reported restriction, not a crash, and the fix is the Flyme keep-alive switches in section 3. With a **secure** lockscreen the screen comes on but the keyguard stays: `wake ok via=activity scr=on kg=secure`, still exit 0. |
| `text hello` | exit 0 with stdout `text 5` (the character count) and `hello` appears in the focused text field — focus one first with `tap`. With no editable field: exit 5 `no editable text field`. |
| `apps` | exit 0, one `<package>\t<label>` line per launchable app (tab separated), sorted |
| `app <package>` | exit 0 with stdout `launched <package>`; that app opens in the foreground |
| `loc` | exit 0, stdout JSON `{"lat":…,"lon":…,"acc":…,"ts":…,"prov":…,…}`; `lat`/`lon` must match the real position |
| `locloop 60` | exit 0 immediately; then `-Action History -Id PHONE-MEIZU` grows by one `loc-<epochMillis>` record per minute |
| `locstop` | the growth from `locloop` stops |
| `stop` | the app's status line goes to `服务: 已停止`; `online` turns false within ~120 s |
| `admin status` | exit 0, several lines: `Device Owner`, `设备管理器`, `静默安装: 不可用…`, `安装未知应用`, one line per development permission with `已授予/未授予`, `无障碍条目` |
| `settings get secure enabled_accessibility_services` | exit 0, stdout `<key> = <value>`; the value must list this app's service |
| `settings put global airplane_mode_on 0` | exit 0, stdout `已设置 global airplane_mode_on = 0`. Without `WRITE_SECURE_SETTINGS`: exit 6 with the exact `pm grant` line |
| `logs 50` | exit 0, up to 50 logcat lines. Without `READ_LOGS`: exit 6 naming `READ_LOGS` |
| `a11y status` on a healthy phone | exit 0, three fact lines `无障碍总开关: 开` / `无障碍条目: 已列出` / `服务实例: 已连接`, a `最近自愈:` line, and `结论: 正常` |
| `a11y status` with accessibility switched off in 设置 | `无障碍总开关: 关`, `结论: 总开关被关闭，执行 a11y heal` — the answer must name the switch, not the entry |
| `a11y status` right after installing over an old build, before the rebuild fires | `无障碍条目: 已列出` + `服务实例: 未连接` + `结论: 条目在但服务未绑定…` |
| **install a new build, then within 60 s run `a11y status`** | `服务实例: 已连接` + `结论: 正常`, with `最近自愈: <time> [启动] 已强制重连…`. This is the v0.4.4 acceptance test: on Flyme the broadcast never arrives, so a `[启动]` label is the proof that the process-start trigger did the work. A `[装包]` label means that ROM delivered the broadcast instead — also fine. Seeing `(本次进程内无记录)` after 60 s is a **failure** |
| `a11y status` on a phone where auto-start is switched **off** | the rebuild still happens: `最近自愈:` shows a timestamped `安装后自愈(…ms)` line within ~30 s of the upgrade. This is the v0.4.3 fix — v0.4.2 returned from the boot receiver before scheduling it |
| `a11y status` before any rebuild in this process | `最近自愈: (本次进程内无记录)` — never a blank value, which would read as "fine" |
| `a11y status` on a phone whose binding is healthy | `最近自愈:` carries a `[启动]` line (the start check always logs, even when it repairs nothing) — silence would make "check never ran" indistinguishable from "nothing to do" |
| `a11y heal` with the entry missing | exit 0, stdout mentions how many existing entries were kept; the accessibility service stays on |
| `a11y heal` with the entry listed but `服务实例: 未连接` | exit 0 and the binding comes back; it must **not** answer `无障碍条目已存在` and stop (the v0.4.2 dead end) |
| `a11y rescue` | exit 0, stdout `已强制重连无障碍服务（保留原有 N 条）`; `a11y status` immediately after shows `服务实例: 已连接` within ~4 s. Other accessibility services are still listed |
| `settings get secure enabled_accessibility_services` right after `a11y rescue` | exit 0, our component is present exactly once and no entry was lost |
| `install <url>` | exit 0, stdout `已拉起安装确认页（需确认安装）: …`; the system installer appears. Ctrl path: `confirm 安装` (dry run — check the node) then `confirm apply 安装` |
| `confirm 安装` | exit 0, stdout `dry-run: would confirm "安装" via=… pkg=com.android.packageinstaller node=… at=…` — the installer is **not** pressed (it takes one look, no polling) |
| `confirm apply t=30 安装` | exit 0, stdout `confirmed "安装" via=action\|ancestor\|gesture pkg=com.android.packageinstaller node=… at=… attempts=…`; the install proceeds |
| `uninstall <pkg>` | exit 0, stdout `已拉起卸载确认页（需确认卸载）: <pkg>` (or `包未安装…（无需卸载）`); then `confirm apply 确定` |
| `lock` | exit 0, stdout `已锁屏`; the screen locks |
| `reboot` | exit 6 `重启需要系统签名权限（REBOOT），侧载应用无法申请；平板已设 DO 也不够，只能手动重启或 root`. There is no configuration that makes this succeed — see SPEC.md §3.5 |
| `adbinfo` | exit 0, exactly these lines: `adb_wifi_enabled = 1`, `本机 IP = <当前 wlan 地址>`, `无线调试端口 = <port>（来源 _adb-tls-connect._tcp）`, `connect = <ip>:<port>`, `TCP 探测 = 可连`. Compare the port with `adb mdns services` on the PC — they must match |
| `adbheal` | exit 0, the same five lines; the connect string must be pasteable into `adb connect` as-is. **It bounces `adb_wifi_enabled` 0 -> 1, so a live adb session drops** — re-connect with the printed string |
| `adbheal` with wireless debugging already on, after `adbinfo` has started returning `未发现` | exit 0 and **must still print a port**: the toggle bounce re-creates the broadcast that NsdManager stopped hearing. If it prints `未发现`, the failure text must name both tiers (`NsdManager 未发现…，裸 mDNS 查询也未收到应答…`) |
| `adbinfo` twice within a minute | the second run reports `来源 = 缓存（Ns 前发现）` and the same port; no second discovery round |
| `adbheal` then `adbinfo` immediately | `adbinfo` reports the fresh cache, and the port matches what `adbheal` printed |
| `dl https://<any host>/film.mp4` | exit 0, stdout exactly `已下载 film.mp4 <n> 字节 → /sdcard/Movies/film.mp4`; `<n>` matches the real file size |
| `stop` then look at the app | the status line says `服务: 已停止`, the button reads `启动`, and the `开机自动启动` checkbox is **unchecked** (stop cleared it) |
| press `启动` | service runs and the checkbox is ticked again — start re-arms auto-start |
| reboot after `stop` | the agent stays down; nothing starts it (this is the whole point of the toggle). After a `启动` press, a reboot brings it back as before |
| `files ls` | exit 0, first line `/sdcard (N 项)`, then `d\|-` rows with size and `yyyy-MM-dd HH:mm`. Open 文件管理 and compare the count |
| `files ls DCIM 5` | at most 5 rows plus `，只显示前 5 项` when DCIM has more |
| `files ls ../etc` | exit 2, `路径不可用: /etc（只允许 /sdcard 内的路径）` — **must not** list anything |
| `files info /sdcard/DCIM` | exit 0, four lines: path, `类型: 目录`, `大小: -`, `修改: …` |
| `files cat <a small text file>` | exit 0, the file's text verbatim |
| `files cat <a video or a >64 KiB log>` | exit 2 with the reason (binary / too large) — never a truncated dump |
| `files mkdir /sdcard/tmp-check` | exit 0; the directory appears in 文件管理. Run it twice: the second is still exit 0 |
| `files rm /sdcard/tmp-check` | exit 0, dry run: names the absolute path and the `files rm apply …` line. **The directory must still exist** |
| `files rm apply /sdcard/tmp-check` | exit 0, the directory is gone |
| `files rm apply /sdcard` | exit 2 — a root is refused unconditionally |
| `files find /sdcard mp4` | exit 0, first line `查找 /sdcard 关键字=mp4 命中 N 项`, then `d\|-` rows ending in the full path. Spot-check one hit in 文件管理 — it must exist |
| `files find /sdcard DCIM jpg 5` | at most 5 rows; with more hits the answer ends `… 还有 N 项未列出` |
| `files find /sdcard zzzznomatch` | exit 0, `查找 /sdcard 关键字=zzzznomatch 命中 0 项` — an empty answer, not an error |
| `files find /sdcard/Android mp4` | `data`/`obb` are never entered: no row's path starts with `/sdcard/Android/data` or `/sdcard/Android/obb` |
| `files find ../etc passwd` | exit 2, `路径不可用: /etc（只允许 /sdcard 内的路径）` |
| `files df` | exit 0, one line `总 X 已用 Y 可用 Z (P%)`; compare with 设置 → 存储 — total and free must be within a few hundred MB |
| `files pull /sdcard/DCIM/<a photo>.jpg` | exit 0, one JSON line `{"name":"file-<millis>-<suffix>.jpg","bytes":N,"path":"…"}`; fetch `/ctl/blob/<name>` and the byte count and the picture must match the phone's file |
| `files pull /sdcard/DCIM` | exit 2 with the reason (a directory cannot be pulled) — never a JSON line |
| `notif` with notification access granted | exit 0, `通知 N 条`, then `- pkg \| title \| text` lines; a music player shows a leading `*` (ongoing) |
| `notif 3` | at most 3 lines plus `（只显示前 3 条）` |
| `notif q=<a word from a visible notification>` | only matching lines; an unmatched word answers `没有匹配的通知` |
| `notif` with notification access **revoked** | exit 0, one sentence containing `cmd notification allow_listener` — no stack trace, no empty success |
| `say 你好` | exit 0, stdout `已朗读 2 字（lang=zh rate=100 pitch=100）` and the phone **says it out loud** |
| `say -l en hello world` | the English voice reads "hello world"; the report says `lang=en` |
| `say rate=200 pitch=90 测试` | faster and lower than the default, and the report echoes both numbers |
| `say -l fr bonjour` | exit 2 (usage) — an unknown language is refused, never read with a Chinese voice |
| `say` with no text | exit 2, usage `say [-l zh\|en] [rate=PERCENT] [pitch=PERCENT] TEXT` |
| `screen stayon on` | exit 0, `已开启常亮（屏幕超时=永不）`; 设置 → 显示 → 休眠 shows 永不; the panel stays lit for 5 minutes untouched |
| `screen stayon` | exit 0, `常亮: 开 当前超时=永不` — and nothing changes |
| `screen stayon off` | exit 0, `已关闭常亮（屏幕超时=60s）`; 休眠 is back to 1 分钟 |
| `screen timeout 120000` | exit 0, `已设置屏幕超时=120s`; then `screen stayon` reports `常亮: 关 当前超时=120s` (a hand-typed timeout clears the flag) |
| `screen timeout 1` | clamped: `已设置屏幕超时=15s` |
| `screen on` while the panel is dark | the panel lights up; if the ROM refuses, the sentence says `但屏幕仍是熄的` — that is a reported restriction, not a crash |
| `screen off` | the panel goes dark **and the phone locks** (device admin must be active) |
| reboot with `stayon on` | after boot, **before** the agent even connects, `screen stayon` reports `常亮: 开` and 休眠 is 永不 — the re-apply runs from `BootReceiver` |
| tick `屏幕常亮` in the app | the checkbox stays ticked and 休眠 becomes 永不; untick it and both revert. With `WRITE_SECURE_SETTINGS` revoked the box springs back unchecked — it never shows a state that is not in force |
| `anim` | exit 0, one line `window=… transition=… animator=…` matching 设置 → 开发者选项 |
| `anim set off` | exit 0, `已把三项动画缩放设为 0`; opening an app has no animation |
| `anim set 0.5` | the three scales read `0.5` in developer options |
| `anim set 99` | exit 2 (usage) — out of range is refused |
| `anim save` then `anim set off` then `anim restore` | restore puts back the values `save` reported; without a prior `save`, restore answers `没有保存过动画设置` |
| `sms` | exit 0, `短信 N 条`, then `<` (received) / `>` (sent) lines, newest first; messages older than 7 days must not appear |
| `sms send 13800000000 测试` | exit 0, **dry run**: `将发送短信到 13800000000: 测试（dry-run…）` and **no SMS is sent** |
| `sms send apply 13800000000 测试` | the SMS arrives on that phone; stdout `已发送短信到 13800000000（2 字）` |
| `sms send abc 测试` | exit 2 — a number that does not normalise is refused before anything is sent |
| `call 13800000000` | exit 0, `将拨号 13800000000（dry-run：未真正拨出…）` and **no call is placed** |
| `call apply 13800000000` | the dialer opens on that number and the call is placed (with `CALL_PHONE`) |
| `call answer` while ringing | exit 0, `已接听`; the call connects. Without `ANSWER_PHONE_CALLS`: exit 6 naming the `pm grant` line |
| `call hangup` during a call | exit 0, `已挂断`; the call ends |
| `call state` | `通话状态: 空闲` / `响铃中` / `通话中` matching the phone |
| `call speaker` with no argument | exit 0, `免提: 开\|关（通话中\|当前无通话）` — read-only, the route must not change |
| `call speaker on` during a call | audio moves to the loudspeaker; `call speaker off` moves it back. Without `MODIFY_AUDIO_SETTINGS`: exit 6 naming the `pm grant` line |
| `call speaker maybe` | exit 2 with the usage line — it must not pick a route for you |
| `admin status` after the v0.4.0 grants | the `扩展权限:` block lists each permission with `已授予/未授予` plus 所有文件访问 / 悬浮窗 / 勿扰权限 / 通知使用权, each carrying the adb line when it is off |

### Family scenario: a film on the tablet (the reason `dl` exists)

1. Get a direct video link (any host, `http` or `https`, no login page).
2. `-Cmd "dl https://<host>/film.mp4"` → exit 0 with the `已下载 … 字节 → /sdcard/Movies/film.mp4` line.
3. On the tablet open **VLC → 浏览 / Internal storage → Movies** — the file is listed and plays. No permission prompt, no reboot, no USB cable.
4. Optional name: `dl https://<host>/a1b2c3 https://… no —` use `dl https://<host>/a1b2c3 奶奶的越剧.mp4` and confirm the file appears under that exact name.

### Negative cases

| Command | Expectation |
| --- | --- |
| `bogus` | exit 2, stderr `unknown command: bogus` |
| `tap` / `tap 1` | exit 2 (usage) |
| `click` / `click   ` / `click apply` | exit 2, usage `click [apply] SEL` (a selector is mandatory) |
| `longclick` / `longclick apply` | exit 2, usage `longclick [apply] SEL` |
| `click no-such-label` | exit 5, stderr `没有出现含「no-such-label」的节点` (dry run) |
| `click apply no-such-label` | exit 5, stderr `no node matched: no-such-label matches=0` |
| `scroll down` on a screen with nothing scrollable | exit 5, stderr `no scrollable node` |
| `scroll sideways` | exit 2, usage `scroll up\|down [n] [SEL]` |
| `ime` / `ime search` | exit 2, usage `ime enter` |
| any command with accessibility **off** | exit 3, stderr `accessibility service not connected` |
| `loc` with location permission revoked | exit 6, stderr `location permission not granted` |
| `app com.does.not.exist` | exit 5, stderr `package not found: com.does.not.exist` |
| `confirm t=4 zzqq_no_such_button` | exit 5, stderr `没有出现含「zzqq_no_such_button」的节点`. **Must not** report success: plain status text is not a button |
| `confirm t=4 服务` | exit 5, stderr `只匹配到不可点文本（N 个），没有可按的按钮` |
| `confirm apply t=4 zzqq_no_such_button` | exit 5, stderr `confirm 超时：4s 内没有出现含「zzqq_no_such_button」的节点（尝试 N 次）` — the `apply` form polls, so its wording names the timeout |
| `settings unknown key` | exit 2 (usage) |
| `confirm` / `confirm t=abc x` / `confirm apply` | exit 2, usage `confirm [apply] [t=SECONDS] TEXT` |
| `adbinfo` with wireless debugging off (`settings put global adb_wifi_enabled 0`) | exit 0, `无线调试端口 = 未发现`, `connect = 无（未发现端口）`, `TCP 探测 = 未做`. **Must not** print a port or a `:port` substring taken from anywhere else |
| `adbinfo 38181` / `adbheal 38181` | exit 2, usage; an argument-taking query hides script typos |
| `dl` / `dl t=300` | exit 2, usage `dl [t=SECONDS] URL [NAME]` |
| `dl /sdcard/x.mp4` | exit 2, usage — a bare path is a typo, not a URL, and must not be quietly prefixed with `http://` |
| `dl https://<host>/does-not-exist.mp4` | exit 5, stderr `错误: 下载失败: HTTP 404（已写 0 字节）`; **no file** in Movies, and no 0-byte ghost in VLC |
| `dl` two of them back to back | the second returns exit 6 `已有下载在进行` while the first still runs; it must not corrupt the first transfer |
| `dl t=1 https://<host>/<a genuinely slow large file>` | exit 4, stderr `错误: 下载失败: 超过 1 秒没有下完（已写 <n> 字节）`, and Movies has no half file |

### Rate limit

Run `shot` twice back to back. The blob path has no size ladder, so a rate-limit
reply is the only failure worth retrying. If the second one returns exit 4 with
`INTERVAL_TIME_SHORT`, that is the platform rate limit, not a bug — wait a
second and retry.

### The controller's `#rc:` protocol

`rc.py` on the managed PC does not send `shot`; it sends a `#rc:` line and then
fetches the blob. Check that path separately, because it must never fall back to
base64 (the controller parses JSON):

| Command | Expectation |
| --- | --- |
| `#rc: shot {"width":1280,"quality":72}` | exit 0, stdout is exactly one line of JSON `{"name":"shot-…","bytes":<n>,"w":1280,"h":<h>,"q":72,"method":"screen"}`, stderr the same `w=/h=/bytes=/blob=` line as `shot`. Fetch `GET <relay>/ctl/blob/<name>` — the image must be the current screen at 1280 px on the long side |
| `#rc: shot {}` | exit 0, defaults apply: `"w":720`, `"q":55` |
| `#rc: shot {"width":99999,"quality":1}` | exit 0, clamped to `"w":1440`, `"q":10` (the controller window is 10..95, wider than the human `shot` window) |
| `#rc: shot {"width":800,"quality":60,"hwnd":123,"rect":[0,0,100,100]}` | exit 0, `hwnd`/`rect` ignored silently — Android has no window handles, and this must not error |
| `#rc: wins` | exit 2, stdout `{"ok":false,"err":"未知内置动作：wins"}`. **Must not** be executed as a shell command and **must not** exit 0 |
| `#rc:` / `#rc: shot {bad json` | exit 2, stdout `{"ok":false,"err":"bad #rc payload: …"}` |
| `#rc: shot` with the relay unreachable (airplane mode) | exit 5, stdout `{"ok":false,"err":"blob upload failed: …"}` — **no base64 fallback**, because the controller would read it as a corrupt reply |

## 6. Reboot persistence and the post-boot adb heal

1. Reboot the phone.
2. Do **not** open the app.
3. Wait 2–3 minutes, then `-Action List`: `PHONE-MEIZU` should be `online: True`
   again, driven by `BootReceiver` + `START_STICKY`.
4. The same list line should now carry `adbwifi=<port>`: `BootHeal` runs about
   20 s after boot, turns wireless debugging back on if it was off, and caches the
   new port. The port changes every reboot, so a port here that matches
   `adb mdns services` on the PC is the proof it worked.
5. Within 10 minutes of boot, `adbinfo` should report
   `来源 = 缓存（Ns 前发现）` — that is the boot heal's cache, not a fresh lookup.

**Before blaming the ROM, check the checkbox.** If the last thing anyone did was
press `停止`, auto-start is off by design and nothing will bring the agent back —
that is the feature, not a failure. Press `启动` (which re-arms it) and reboot
again.

If the app does not come back, the cause is almost always a missing Flyme
keep-alive switch from section 3 — re-check those before suspecting the code.
If the app comes back but `adbwifi=` is missing, look at `logs 200` for the
tag `adb` line `开机自愈: …`, which names the failed tier. A missing field is not a
bug by itself: `adb_wifi_enabled = 0` on a device where the settings write is
refused leaves nothing to discover.

## 7. What this app deliberately cannot do

No arbitrary shell execution and no root. `INSTALL_PACKAGES` /
`DELETE_PACKAGES` are `signature|privileged` with no `development` flag, so a
sideloaded app cannot hold them **even as Device Owner** — `install`/`uninstall`
go through the system confirmation UI and `confirm apply` presses the button.

What `pm grant` *can* grant — `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `DUMP`,
`PACKAGE_USAGE_STATS` — is used, and reported honestly per command. See
`README.md` and SPEC.md §3.3.

`dl` writes only inside `/sdcard/Movies` and only over `http`/`https`; there is no
`rm`, no move, no arbitrary path, and nothing is ever written outside that
directory (SPEC.md §3.6). It also does not run when nobody asked: no scheduled
downloads, no background syncing.

`reboot` is in the same category and always answers exit 6: the permission it
needs (`REBOOT`) is signature-level, and the Device Owner door needs a
factory-reset provisioning this build never asks for. See SPEC.md §3.5. `lock`
does work, because a device admin can be activated by hand.

`call` controls a call but never carries its audio — see 2.7. `files` cannot
leave `/sdcard`, and `files rm` is a dry run until `apply`; there is no move,
no copy and no arbitrary path. `sms send` and `call NUMBER` are dry runs until
`apply`, because both reach a real person and neither can be recalled.
