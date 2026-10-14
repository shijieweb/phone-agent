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

> The relay base url is `https://mcp.owen1.de5.net`, not the direct
> `https://dsh.owen1.de5.net:19092`. On at least one carrier the direct address
> has a forged RST injected into the first TLS flight, so the app can never
> complete a handshake there. Upgrading from v0.2.0 or earlier migrates a stored
> copy of the old default automatically — you only need to type the new value if
> you had entered some other address by hand.

## 0. Values to type into the app

| Field | Value |
| --- | --- |
| Relay base url | `https://mcp.owen1.de5.net` (the Cloudflare-fronted name — see the note below) |
| Agent id | `PHONE-MEIZU` |
| Secret | the contents of `/etc/rc-agent/secret` on the VPS (64 hex chars) — ask the operator, do **not** commit it |

## 1. Install

1. Download `phone-agent.apk` from `https://dsh.owen1.de5.net:19092/dl/phone-agent.apk`
   (HTTP basic auth, user `dsh`).
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
| `shot` | exit 0, stdout is one long base64 line (no newlines, no `data:` prefix); stderr `w=… h=… bytes=… b64=…`. Decode it and confirm the picture is the current screen. |
| `shot 320 30` | exit 0, stderr shows a smaller `w=`/`h=` |
| `tree` | exit 0, one line per node, each `<depth>\|<class>\|<text>\|<desc>\|<resId>\|<bounds>\|<flags>`. Open the app drawer and re-run — the lines must change. |
| `tap X Y` | exit 0 with stdout `tap X Y`; the thing at those coordinates reacts (verify with `shot` before/after). If the gesture never completes you get exit 4 `gesture did not complete`. |
| `long X Y 800` | exit 0 with stdout `long X Y 800`; a long-press menu opens |
| `swipe 540 1600 540 600 300` | exit 0 with stdout echoing all five numbers; the screen scrolls up one page |
| `key back` | exit 0 with stdout `key back`; goes back |
| `key home` | goes to the launcher |
| `key recents` | opens the recents list |
| `key notifications` | pulls down the notification shade |
| `key quicksettings` | expands quick settings |
| `text hello` | exit 0 with stdout `text 5` (the character count) and `hello` appears in the focused text field — focus one first with `tap`. With no editable field: exit 5 `no editable text field`. |
| `apps` | exit 0, one `<package>\t<label>` line per launchable app (tab separated), sorted |
| `app <package>` | exit 0 with stdout `launched <package>`; that app opens in the foreground |
| `loc` | exit 0, stdout JSON `{"lat":…,"lon":…,"acc":…,"ts":…,"prov":…,…}`; `lat`/`lon` must match the real position |
| `locloop 60` | exit 0 immediately; then `-Action History -Id PHONE-MEIZU` grows by one `loc-<epochMillis>` record per minute |
| `locstop` | the growth from `locloop` stops |
| `stop` | the app's status line goes to `服务: 已停止`; `online` turns false within ~120 s |

### Negative cases

| Command | Expectation |
| --- | --- |
| `bogus` | exit 2, stderr `unknown command: bogus` |
| `tap` / `tap 1` | exit 2 (usage) |
| any command with accessibility **off** | exit 3, stderr `accessibility service not connected` |
| `loc` with location permission revoked | exit 6, stderr `location permission not granted` |
| `app com.does.not.exist` | exit 5, stderr `package not found: com.does.not.exist` |

### Rate limit

Run `shot` twice back to back. If the second one returns exit 4 with
`INTERVAL_TIME_SHORT`, that is the platform rate limit, not a bug — wait a
second and retry.

## 6. Reboot persistence

1. Reboot the phone.
2. Do **not** open the app.
3. Wait 2–3 minutes, then `-Action List`: `PHONE-MEIZU` should be `online: True`
   again, driven by `BootReceiver` + `START_STICKY`.

If it does not come back, the cause is almost always a missing Flyme
keep-alive switch from section 3 — re-check those before suspecting the code.

## 7. What this app deliberately cannot do

No arbitrary shell execution, no silent app install/uninstall, no root. These
need either root or Device Owner (which requires a factory reset), so they are
out of scope by design. See `README.md`.
