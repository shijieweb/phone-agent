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
| `stop` then look at the app | the lamp turns red, the line says 已停止, the button reads `启动`, and `watchdog status` shows 停止标记: 存在 (stop disarmed the guard) |
| press `启动` | the lamp turns green and the service runs; the guard re-arms by itself within 5 minutes |
| reboot after `stop` | the agent stays down; nothing starts it |
| reboot after `启动` | **also stays down since v0.6.3-pad12** — the owner asked for 开机不自启. The phone repairs itself (stay-on timeout, accessibility binding, ADB port) but the agent waits for a human to press `启动` |
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
| `shinfo` before pairing | exit 0, six lines: `自连 ADB: 未连接`, `密钥: …`, `地址: 127.0.0.1（只连本机回环…）`, `通道: PTY（shell:）`, the two mDNS service names, and `最近:` |
| `sh ls /sdcard` | exit 0, stdout is exactly `dry-run: would run sh: ls /sdcard` — **nothing runs** |
| `sh apply id` | exit 0, stdout ends with `uid=2000(shell)` and `exit=0`. If it says `尚未配对`/`尚未连接`, that is exit **6**, not a command failure |
| `sh apply ls /nope` | exit **0** with stdout `ls: /nope: No such file or directory` + `exit=1` — the remote code is in stdout, not in our exit |
| `sh apply echo __RC=9` | exit 0, stdout contains `__RC=9` **and** a final `exit=0`: only the last sentinel counts |
| `sh t=1 apply sleep 5` | exit 4, stderr `命令超时（1s 内没有返回）`; the phone must not stay wedged — `sh apply id` works right after |
| `sh apply echo apply` | exit 0, stdout `dry-run: would run sh: echo apply` — the `apply` here is payload, not a flag |
| `shkey` | exit 0, `公钥: <one long base64 line> phone-agent@dsh` and `指纹: SHA256:<43 chars>`; the same fingerprint appears in 手机 无线调试 → 已配对的设备 |
| `shkey regen` | exit 0, **dry run**: `dry-run: 会删除现有密钥并重新生成…` and `shkey` still prints the old fingerprint |
| `shkey show` | exit 0, three lines: `来源: 自生成` (or `导入`), `公钥: …`, `指纹: SHA256:…` — the same fingerprint `shkey` prints |
| `shkey import <url>` (bad URL) | exit 6 with `导入失败：下载私钥失败（…）`; `shkey` must still print the **old** fingerprint — a failed import never damages the working key |
| `shkey import <url>` (good pair) | exit 0, `已导入密钥 <name> 指纹 SHA256:…` then `现在可以执行 shconnect…`; `shkey show` says `来源: 导入`; `shconnect` succeeds **without** pairing |
| `shkey import <url>` (private/public mismatch) | exit 6 with `导入失败：私钥与公钥不是一对（adb 公钥结构体对不上）` — swap one half and re-run to see it |
| `shkey adopt` | exit **6** with the explanation that `/data/misc/adb/adb_keys` is adbd-private and points at `shkey import`; it must not claim success |
| `sh apply id` on the v0.5.4 build | exit 0, stdout has the `uid=2000(shell) …` line and then `exit=0` **on its own line**. On v0.5.3 the same command returned exit 4 with 连接被对端关闭: this ROM closes every `OPEN` that carries a command (`kRaw`), which is why the PTY path exists |
| `shinfo` channel line | `通道: PTY（shell:）` on this device. `通道: RAW（降级 shell:COMMAND）` means opening the PTY stream itself failed — on Flyme that should never happen, and raw will then fail every command |
| `shdest shell:` | exit 0, `结果: 超时（8s 内没有读完）` with 12 bytes and text `Meizu22:/ $ ` — the prompt proves `kPty` works |
| `shdest shell:id` | exit 0, `结果: 失败 java.io.IOException: Stream closed.` in ~0.0 s — this is the ROM bug the PTY path routes around, not a wrong destination |
| `sh apply ls /data/misc/adb` | exit 0 with merged output `ls: /data/misc/adb: Permission denied` then `exit=1` — proves stderr comes back and the remote code is 1 |
| `sh apply pm list packages net.de5.phoneagent` | exit 0, `package:net.de5.phoneagent` then `exit=0`. Quote-free form works: everything after the flags is payload |
| `shpair 000000` after the v0.5.1 build | exit 6, and if the system blocks the exporter the text is exactly `配对失败：本机系统禁止非 SDK 接口（exportKeyingMaterial）。请改用 shkey import <url> 导入一把已被手机授权的密钥。` — **not** a stack trace |
| `shpair 000000` (wrong code) | exit 6 with `配对失败: …`; the old pairing (if any) must still work |
| `shpair 12345` | exit 2 — a code that is not six digits is refused before any network work |

| `watchdog status` | exit 0, seven lines. With self-ADB connected: `守护: 运行中（pid N）` or `未运行`; with it down: `守护: 未知（自连 ADB 未连接）`. `停止标记: 不存在` in normal operation. `status` JSON gains `"guard":"running"\|"off"\|"unknown"` and every other field is unchanged. |
| `watchdog arm` | exit 0, `守护已启动（pid N）`; run it three times in a row and `watchdog status` must still show **one** pid with `…未重复启动` on the repeats |
| `watchdog disarm` | exit 0, `守护已停止`; `watchdog status` then shows `未运行` + `停止标记: 存在`, and no `phoneagent-guard.sh` process remains |
| `watchdog arm` with self-ADB down | exit 6, stderr `守护启用失败: 自连 ADB 未连接（先执行 shconnect）` — a refusal, not a crash, and the heartbeat retries by itself once `shconnect` works |
| `stop` (relay command) | exit 0, stdout `stopping`; the service stops **and** `auto_start` becomes false. Prove it: wait 2 minutes and the agent must not come back, then open the app — it must stay stopped. This is the v0.5.6 fix; on v0.5.5 the guard revived it after 60 s |

### 5.1 Self-ADB: pair the app with its own adbd

This is the only part of the surface that needs a ritual, because Android's
wireless debugging is a two-step handshake and the app is talking to **itself**.

1. 设置 → 系统 → 开发者选项 → **无线调试** → on.
2. Tap **使用配对码配对设备** — a six-digit code and a port appear. Keep that
   dialog open; it closes on success.
3. `shpair 123456` (the code from step 2). Expect
   `配对成功，密钥已写入手机；接着执行 shconnect 建立连接` followed by
   `已连上本机 adbd（uid 2000）…` on the next line.
4. `shinfo` → `自连 ADB: 已连接`, and `密钥: 已生成（adb/adbkey） 指纹 SHA256:…`.
5. `sh apply id` → `uid=2000(shell)`. **That single line is the whole proof**: an
   app in the `untrusted_app` domain cannot print that.

If step 3 answers `没发现 adb-tls-pairing 端口…`, the mDNS broadcast was missed
(common on Flyme). Wait 3–5 s and retry; the app also bounces
`adb_wifi_enabled` itself when `WRITE_SECURE_SETTINGS` is held. If it answers
`TLS 握手被拒`, the key is not authorised — redo step 2, the code changes every
time the dialog opens.

After a reboot the pairing survives; only `shconnect` is needed. After
`shkey regen apply` the pairing is **gone** — delete the old `phone-agent@dsh`
entry under 已配对的设备 and start again from step 2.

### 5.2 Self-ADB when pairing is impossible (Android 16)

On Android 16 the pairing step above cannot complete: it needs the TLS exporter
(`Conscrypt.exportKeyingMaterial`), which is a non-SDK interface, and hidden-API
enforcement blocks the reflective lookup. Step 3 then answers with the
`本机系统禁止非 SDK 接口` sentence instead of a stack trace.

The way in is to borrow a key the phone already trusts:

1. On a PC that has already been authorised by this phone, take its
   `adbkey` + `adbkey.pub` and serve them over http (any static host works;
   the app's network config only permits cleartext to the known relay hosts).
2. `shkey import http://<host>/adbkey` — the `.pub` URL defaults to `<url>.pub`.
3. `shconnect` — **no pairing**, because `/data/misc/adb/adb_keys` on the phone
   already contains that public half.
4. `sh apply id` → `uid=2000(shell)`. That is the whole proof.

If the import refuses, the reason names the half that is wrong (`私钥不是 PKCS#8
PEM`, `公钥只有 N 字节`, `私钥与公钥不是一对`), and the previous key keeps
working — the import only swaps files after every check passes.

**If `shkey import` succeeds but `shconnect` still answers `设备要求先配对`**, the
import itself worked and the phone simply does not have that public half in
`/data/misc/adb/adb_keys`. Check it without guessing: the fingerprint printed by
`shkey show` must appear under 无线调试 → 已配对的设备 on the phone. If it does
not, authorise that key once from a PC (`adb connect <phone-ip>:<port>` and accept
the prompt) and re-run `shconnect`; re-importing changes nothing, because the key
bytes are already correct.

### 5.3 Self-start: proving the phone comes back on its own

Do this on the phone with the agent running (press `启动` until the lamp is green)
and self-ADB connected (`shinfo` shows `自连 ADB: 已连接`).

1. `watchdog status` → `守护: 运行中（pid N）`. If it says 未运行, run
   `watchdog arm` once and check again.
2. From the PC: `sh apply am force-stop net.de5.phoneagent` → exit 0. The relay
   now reports `PHONE-MEIZU` offline, and `watchdog status` cannot answer because
   the app that would answer is gone.
3. Wait 90 s **without touching the phone**. `python scripts/rc.py list` must show
   `PHONE-MEIZU` online again, with a fresh `age_sec`. That is the guard: it ran
   `am start`, the activity started the service (§3.17 trigger 1), and the service
   re-armed the guard.
4. `watchdog status` → `运行中`, and `最近重启` shows the timestamp of step 2.
   The same line is in `/data/local/tmp/phoneagent-guard.log`.
5. Now prove stop still means stop: in the app press 停止, then
   `sh apply am force-stop net.de5.phoneagent`. Wait **two minutes**. The agent
   must **not** come back — the stop marker makes the guard exit at its next tick.
   `watchdog status` answers only after you start the agent again.

**Arming never stacks copies.** Run `watchdog arm` three times in a row and then
`watchdog status`: exactly one pid, and the second and third answers contain
`未重复启动`. A phone left running for a day re-arms every 5 minutes; without
this rule it would collect roughly 288 copies.

**On mobile data the guard is unavailable, and that is expected.** Self-ADB
discovers its port over mDNS, and mDNS multicast does not leave a cellular
interface. On cellular (`adbinfo` shows an address like `10.201.131.111`),
`adbheal` flips `adb_wifi_enabled` to 1 but neither `NsdManager` nor a raw mDNS
query resolves, `shconnect` fails, and `watchdog arm` answers
`守护启用失败: 自连 ADB 未连接`. Do not read that as a bug: only step 1 (opening
the UI) can restart the agent on cellular. Run the whole of 5.3 on Wi-Fi.

**What this cannot prove.** The guard lives in `/data/local/tmp`, so a reboot
wipes it — and since v0.6.3-pad12 `BootReceiver` does not start the agent either,
so after a reboot a human must press `启动` (§6). A ROM that aggressively reaps
`uid 2000` background processes can kill the guard too; the app cannot prevent
that, only notice it the next time it is opened.

### 开机不自启, and the guard's two new rules (v0.6.3-pad12)

The owner asked for one thing in this version: the phone must never start the
agent by itself. Steps 1-3 prove that; 4-6 prove the two guard repairs that came
out of the same test round.

1. Press `启动`, wait for the green lamp, then reboot the phone and **do not
   touch the app afterwards**. The relay must report the phone offline
   indefinitely: no `am start`, no `START_STICKY` resurrection, no guard
   (`/data/local/tmp` is wiped by the reboot). Before v0.6.3 `BootReceiver`
   started the service here.
2. Now open the app: the lamp goes green by itself and the button reads `停止`.
   That is the only automatic start left, and it needs somebody to open the app.
3. Press `停止`, reboot, then open the app: it must **stay down** and the button
   must read `启动`. A phone that was deliberately silenced cannot be revived by
   opening it.
4. `watchdog status` on a phone still running a v0.5.8 guard script, then
   `watchdog arm`: the answer is
   「已换掉旧脚本并启用守护（pid …, 版本 v0.6.3-pad12, 每 60s 检查一次）」, and a
   second `arm` right after answers 未重复启动. Before v0.6.3 the first `arm` said
   未重复启动 and the old script stayed forever, so a fix to the script could never
   reach a phone in the field.
5. `watchdog disarm` must really stop it: `pgrep -f phoneagent-guard.sh` is empty
   within a second and `watchdog status` says 未运行. The old `pkill -f`
   (SIGTERM) could not kill the loop while the shell sat inside `sleep 60`, and
   reported 还有进程 3004 for a process that was already gone. The new command is
   `pkill -9 -f` three times, 0.5 s apart.
6. Line 2 of `/data/local/tmp/phoneagent-guard.sh` must read
   `# ver=v0.6.3-pad12`. Anything else means `arm` did not replace the old copy —
   which is exactly the regression this check exists for.
### `tree` must see a layout change without re-activating the window (v0.6.0-pad12)

This is the bug the whole version exists for. Run it in the order it was found,
because every step is a check:

1. On the config page leave 高级设置 collapsed. `tree 200` over the relay and
   count lines whose class is `android.widget.EditText` → **0**.
2. `click apply 高级设置`. The reply is `clicked via=action ...` and a screenshot
   shows the three fields and 保存.
3. `tree 200` **immediately** → **3** `EditText`. Before this version it was 0,
   and stayed 0 after six seconds and after a scroll; that is the regression to
   watch for. If it is 0, the active window is being read through
   `AccessibilityWindowInfo.getRoot()` again (cached snapshot) instead of
   `rootInActiveWindow`.
4. `click apply 高级设置` again to collapse it, then `tree 200` → back to **0**.
   A tree that only ever grows is the other half of the same bug.
5. Open a system page with a list (Settings → any screen with menu rows) and
   `tree`; those rows must be there.
6. `click apply 保存` — a node that only appeared in step 2 — must be found, not
   answered with `no node matched`. That error on a node you can see means the
   tree being searched is the stale one.
7. Time it: `tree` must stay **under 500 ms** on this phone. It reads the screen
   twice now, 150 ms apart, so a slow answer means something else got slow.
8. With an IME open, `tree` must still show **both** the keyboard window and the
   app window: the fresh root is merged with the other windows, not substituted
   for them, and neither may appear twice (a duplicate would double every match
   count and make `click apply` ambiguous).

### `tree` and `tree uia` must agree on a fresh screen (v0.6.1-pad12)

The bug from v0.6.0 was that the accessibility channel kept answering with the
window as it was at the last window event, so `rootInActiveWindow` and
`refresh()` were not enough. This version adds two independent mechanisms; run
the check with 高级设置 collapsed at the start.

1. `tree 200` → count `EditText` lines → **0**. Then `click apply 高级设置` and
   `tree 200` **immediately** → **3**. If this step alone now works, mechanism A
   (subscribing to `typeWindowContentChanged`) did it.
2. At the same moment run `tree uia 200` → **3** as well. This is the reading
   that does not depend on our accessibility channel at all: it comes from
   `uiautomator dump` as uid 2000. `sh cat /data/local/tmp/phoneagent-tree.xml`
   must show the same screen; the file is deliberately left behind.
3. `click apply 高级设置` to collapse, then `tree 200` and `tree uia 200` → both
   **0**. A pair that disagrees here means the two channels are looking at
   different moments; note which one is behind and in which direction.
4. Turn the self-ADB link off (or reboot and do not `shconnect`), then
   `tree uia 200` → `exit=6` and the stderr names `shconnect`. It must **not**
   quietly fall back to the accessibility tree.
5. Time both: `tree` under 500 ms, `tree uia` about 1–2 s (15 s budget). A slow
   `tree uia` that still answers is a slow dump, not a hang.
6. `tree uia 5` → exactly five lines and the closing `... truncated at 5 nodes`,
   same wording as `tree 5`.

### The status lamps tell the truth (v0.6.2-pad12)

The page has one job for the phone's owner: answer 现在到底好不好 without her
having to interpret four separate facts. Run this with the app in the foreground
and the screen on.

1. Agent started, accessibility on, battery whitelisted: the big lamp is **green**
   and the word is 运行中, the small line says 一切正常, and all three small lamps
   (无障碍 / 电池 / 自连ADB) are green.
2. Press 停止. Within **3 seconds** the big lamp turns **red**, the word becomes
   已停止, and the small line says 服务没有在跑 · 点下面的「启动」. The three small
   lamps keep showing their own state — that is deliberate: a stopped service is
   red on its own.
3. Press 启动 again → green within 3 seconds.
4. Turn accessibility off in the system settings, come back: within 3 seconds the
   lamp turns **amber**, the word is 部分异常, and the line names 无障碍已关闭 and
   ends with 建议点下面的一键设置权限. Nothing was cached — the page was not
   restarted.
5. Now also remove the battery whitelist (系统设置 → 电池 → 不做优化 → 取消):
   the line must read 无障碍已关闭 · 电池白名单未加入 · 建议点下面的一键设置权限, in
   that order. **Order matters**: it comes from the code, not from a set.
6. With everything green again, drop the self-ADB link (or reboot and do not
   `shconnect`): the line names **only** 自连 ADB 未连接 and points at
   `shconnect`, never at 一键设置权限 — that wizard does not touch ADB, and
   sending the owner there makes the button look broken.
7. Put the app in the background (`key home`) for half a minute, then come back:
   the values must be correct **immediately** on return (the page refreshes in
   `onResume`), not after the next 3 s tick.
8. The controls themselves must be untouched: 启动/停止, 屏幕常亮, 一键设置权限,
   高级设置 (still collapsed) and 保存 inside it — five clickable controls, in that
   order, with those exact words (`click apply <text>` still drives the phone). The
   开机自动启动 checkbox is **gone** since v0.6.3-pad12; if it is still on screen the
   old APK is installed.
9. `tree 200` on this page must show the lamp labels as plain text, not as
   buttons: a lamp that could be clicked would be a lamp the operator presses by
   accident.

### The debug page (v0.5.7-pad12)

1. Open the app and scroll to the bottom. The last line is just `v0.5.7-pad12` —
   no hint, no button, nothing that says it does anything.
2. Tap it **five times within three seconds**. The debug page opens. Go back,
   tap it once, wait five seconds, tap four more times: it must **not** open
   (the presses aged out).
3. On the debug page, the summary reads 版本 / 服务 / 无障碍 / 电量 / 自连 ADB.
   With the agent started, 服务 shows 运行中; press 停止 on the config page and
   come back — it shows 已停止 within 2 s.
4. Type `status` and press 执行. The answer starts with `exit=0` and carries the
   same JSON the relay would return. Type `bogus` → `exit=2` and
   `unknown command: bogus`.
5. Press 节点树 tree 30 → an `exit=0` node dump, clipped with
   `…（已截断，共 N 字符，只显示前 M 字符）` if it is long. Press 截图 shot →
   the shot's one-line JSON, not a freeze.
6. Stop the agent on the config page, come back to the debug page, press 执行 on
   `status`: the panel says 服务未运行，先回配置页点启动. It must not crash and
   must not silently do nothing.
7. Press 诊断 Diagnostics.run → 正在诊断… for up to a minute, then the clipboard
   holds the full report and the panel shows a clipped preview. Press
   状态与日志 → instant, zero network, clipboard again.
8. Press 返回配置页 → back to the config page, agent state untouched.
9. The debug page must not appear in recents, and nothing outside the app can
   start it (`adb shell am start -n net.de5.phoneagent/.DebugActivity` is
   refused: not exported).

### Log size and trimming (v0.5.8-pad12)

1. On the debug page the summary carries a fourth line: `日志: N 行/丢弃 M · 守护日志 X KB · APK 残留 K 个`.
   N never exceeds 400. The first measurement takes one self-ADB round trip, so
   it appears up to 30 s after the page opens (or press 日志占用 logs size).
2. `logs size` from the relay prints the same three ledgers, each with its cap
   and what the cap has thrown away. The guard line says 不可用（原因） when the
   log has never been armed — it must never print `0 B` for a file that does not
   exist.
3. `logs trim` (no argument) is a **dry run**: it lists the guard log and every
   cached APK older than 24 h, then ends with
   `dry-run: 没有删除任何东西，加 apply 才真删。`. Check with `logs size` that
   nothing shrank.
4. `logs trim apply` really does it: the guard log drops to its last 200 lines
   when it was over 64 KB, stale APKs disappear, and the reply ends with 已按上面执行。
   Re-running `logs trim apply` immediately is harmless (it reports 不动 / under).
5. Let the phone sit a day (or `dd` junk into `/data/local/tmp/phoneagent-guard.log`
   until it passes 64 KB), then wait for the next watchdog beat: the file shrinks
   to ≤200 lines **by itself**, with the app never opened. That is the point —
   the loop outlives the app.
6. Nothing here may touch `/sdcard/Movies` or any downloaded film: trimming is
   limited to `cacheDir` and the guard log.

### `logs` when self-ADB is down (v0.5.9-pad12)

1. With self-ADB **connected**, `logs 20` returns lines and **no system dialog**
   appears. On Android 16 the app's own uid is what triggers
   `LogAccessDialogActivity`; uid 2000 is never asked. That is the whole reason
   this path exists.
2. Disconnect self-ADB (`shdisconnect` or stop the pairing), then `logs 20`. The
   reply is a refusal that **redirects**: it names the 允许访问一次 dialog, says it
   re-appears every time so it cannot be answered remotely, says `pm grant READ_LOGS`
   也没用 (granted=true and the dialog still appears — verified on the real phone),
   and ends with 建议先 shconnect 连上自连 ADB 再执行 logs，或直接 sh apply logcat -d -t 20.
   A message that only says READ_LOGS 未授予 is a regression: it sends the operator
   to a grant that does not help.
3. Grant `READ_LOGS` anyway (`adb shell pm grant net.de5.phoneagent
   android.permission.READ_LOGS`) and repeat step 2: the dialog still appears and the
   reply is unchanged. Nothing in the app may claim that grant fixed it.
4. `logs size` and `logs trim` are **not** affected — they measure files, not
   logcat, and must behave exactly as in the previous section with self-ADB down.

### Two devices on one Wi-Fi: connect to yourself, not to the neighbour (v0.6.4-pad12)

1. Put the phone and the tablet on the same Wi-Fi with wireless debugging on for both.
   On the desktop, `adb mdns services` shows **two** `_adb-tls-connect._tcp` entries with
   different addresses — that is the condition this guards against.
2. On the tablet: `shconnect`. It must either connect to the tablet's own port or say
   没发现 …端口 — it must **never** spend 30 s and end with 连接超时 on a port that
   belongs to the phone.
3. When it fails, the reply ends with 已忽略 N 个其它设备的广播（如 192.168.0.106:40157）.
   The address in the example must be the *other* device's, and the port must be the one
   that device really listens on (`adb -s <other> shell …` or the desktop `adb connect`
   check). N counts every foreign advertisement dropped, not every packet seen.
4. `adbinfo` / `adbheal` on the tablet: still exactly six lines, and the count appears
   inside the existing `说明 = ` line. A seventh line is a regression.
5. Turn the phone's wireless debugging off and repeat `shconnect`: the ignored count must
   drop to zero (or the line disappears) and the port found must be the tablet's own.
   This proves the gate rejects by address, not by luck of ordering.

### `shkey import` on Android 12 (v0.6.4-pad12)

1. On the tablet (Lenovo TB-J607F, Android 12 / API 31): `shkey import` with the same
   private/public pair that works on the Android 16 phone. It must answer 导入成功 and
   print the fingerprint — previously it always failed with
   导入失败：私钥不是 RSA CRT 私钥（拿不到模数）. Exit code 6 on this step is the bug.
2. `shkey show` right after: source reads imported, and the fingerprint equals the one
   printed by import. Then `shconnect` must still work with the imported key — loading a
   key goes through the same code path that used to fail, so a passing import with a
   broken load is not a pass.
3. Delete and regenerate (`shkey delete`, then `shconnect`): the generated key must load
   on the same device. Generation always produced a CRT instance, so this checks the
   fallback did not break the normal path.
4. Feed it something that is not an RSA key (e.g. an EC private key in PKCS#8): the reply
   is 导入失败：这把私钥不是 RSA 私钥，或者 PKCS#8 结构不认识. It must not mention CRT and
   must not claim the key is corrupt.

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

No root, and no shell on any machine but this one. Since v0.5.0 `sh` does run
commands — but as **uid 2000** through the phone's own `adbd` over `127.0.0.1`,
which is exactly the authority a USB `adb shell` has and nothing more. There is no
`sh HOST` form, no connecting to the PC, and `pm grant` of a signature permission
fails from there too. `sh apply id` printing `uid=2000(shell)` is the honest
statement of the ceiling; `uid=0` would be a bug.

`INSTALL_PACKAGES` / `DELETE_PACKAGES` are `signature|privileged` with no
`development` flag, so a sideloaded app cannot hold them **even as Device Owner** —
`install`/`uninstall` go through the system confirmation UI and `confirm apply`
presses the button. The shell user cannot hand them over either: uid 2000 is not
`system`.

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
