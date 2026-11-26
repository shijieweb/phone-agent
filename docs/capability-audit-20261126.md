# phone-agent 能力复核记录（2026-11-26）

> 起因：老板 m06546 要求「前6个是否都有价值你等会再重新看看」。逐条对着源码 grep + 真机实测复核，
> 结论按「已有 / 确认没有 / 有但不完整」三类，路径都是相对 D:/phonectl/phone-agent。

## 一、已有（我上一轮口头说「没有」的，说错了）

| 能力 | 证据 | 真机验证 |
|---|---|---|
| 通知监听 | `NotifListenerService.kt:14 class NotifListenerService : NotificationListenerService()`；`NotifPlan.kt:10 internal object NotifPlan`（LIMIT_DEFAULT=30 / LIMIT_MAX=200 / TITLE_MAX=80 / TEXT_MAX=160）；`Commands.kt:709 "notif" -> parseNotif(rest)`；`Executor.kt:252 is Command.NotifList` | 原来回「没有可读通知（通知使用权未开启）」，执行 `sh apply cmd notification allow_listener net.de5.phoneagent/net.de5.phoneagent.NotifListenerService` 后立刻读出 2 条 |
| 中文输入 | 走 `ACTION_SET_TEXT`（`Executor.kt:1346`），不是 `input text`，中文本来能进 | 微信/设置里实测可输入 |
| 短信直读 | `SmsOps.kt:17 private val INBOX: Uri = Uri.parse("content://sms")`；`Executor.kt:1093 SmsOps.list` / `:1125 SmsOps.send` | `sms` / `sms send` 可用 |
| 屏幕常亮开关 | `screen stayon on` → 「已开启常亮（屏幕超时=永不）」 | 平板实测生效 |
| 日志封顶 | `WatchdogPlan.kt:50 LOG_MAX_BYTES = 64*1024`、`:53 LOG_KEEP_LINES = 200`；`LogSizes.kt` 提供 `logs size` / `logs trim` | 两条裁剪路径都实测过 |

## 二、确认没有（真缺口，按价值排序）

1. **音频焦点 / AudioAttributes**（影响最大，直接决定 say 能不能压住音乐）
   全仓 grep `requestAudioFocus|AudioAttributes|setAudioAttributes|MediaSession` = 0 命中。
   `SayOps.kt:33 TextToSpeech(context.applicationContext)` 每次新建（`INIT_TIMEOUT_SECONDS = 10L`）、
   `:73 val params = Bundle()` 空 Bundle、`:75 engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)`。
   SayOps.kt:20-23 注释写「One engine per utterance on purpose」——怕常驻 binding 变成「一周后没声音」。
   → 现在念一段话时如果系统在放音乐，两者会混着响，用户听不清。

2. **waitfor 条件等待**
   全仓 grep `"wait"` 只命中 `RcClient.kt:70 body.put("wait", waitSeconds)`（长轮询参数，不是能力）。
   → 只能靠外部轮询 tree，没有「等这个文字出现」的一条命令。

3. **自动亮屏编排**
   `wakeInTap` 零命中；`Wake.wake(context)` 只在 `Executor.kt:157 is Command.Wake` 手动触发；
   tap / click / tree / shot 前都不检查 `isInteractive`。
   `Wake.kt:239 if (manager.isKeyguardLocked && !manager.isKeyguardSecure)` 明确跳过安全锁屏；
   `:254 else if (manager.isKeyguardSecure)` 只用于上报 `kg=secure`。
   → 熄屏时点不中、截不到，得先手动 `wake`。

4. **secure 锁屏要明说**
   现在 wake 后若 `kg=secure` 只是把状态报出来，后续 click 会静默回「没找到节点」，
   看起来像选择器写错了。应该直接回「锁屏有密码，界面操作不可用」。

5. **媒体键**
   `Commands.kt:467 private val KEY_NAMES = setOf("back", "home", "recents", "notifications", "quicksettings")`、
   `:561 if (!KEY_NAMES.contains(name)) return Command.BadUsage("unknown key: $name")`。
   真机 `sh apply input keyevent 87` exit=0 可用，但没有 `media pause|play|next|prev` 这层包装。

6. **页面判断**
   Kotlin 侧 `mCurrentFocus|topResumedActivity` 只命中 `WatchdogPlan.kt:115`（守护脚本里的 dumpsys activity services）。
   真机 `sh apply dumpsys window | grep -m1 mCurrentFocus` 可用
   （实测回 `mCurrentFocus=Window{e21ab69 u0 com.tencent.mm/com.tencent.mm.ui.chatting.variants.ChattingMainUI}`，
   附带 `Failed to write while dumping service window: Broken pipe` 但 exit=0）。
   → 判断「现在在哪个页面」得自己拼 shell。

7. **通话记录 / 联系人**：ContactsContract、CallLog 全仓 0 命中。
8. **按窗口截图**：takeScreenshotOfWindow / getWindowsOnAllDisplays 零命中。
9. **主动发通知**：`cmd notification post` 零命中（现有 allow_dnd / allow_listener 只在 Admin.kt:168/:170 的提示文案里）。
10. **电池白名单只有读没有写**：`Battery.kt:32 fun isExempt` 只调 `isIgnoringBatteryOptimizations`，没有 deviceidle whitelist 写入。
11. **裸流录屏**：screenrecord 零命中。真机 `sh apply screenrecord --time-limit 3 --size 480x1068 --bit-rate 1000000 /data/local/tmp/t.mp4` exit=0，3.6s 出 28099 字节 mp4。
12. **wm 系列**：零命中。真机 `sh apply wm size` → `Physical size: 1200x2670`；`wm density` → `Physical density: 520`。

## 三、有但不完整

- **中文输入兜底**：`Executor.kt:1339 val root = service.rootInActiveWindow ?: return SET_TEXT_NO_FIELD`、
  `:1340 val target = findEditable(root) ?: return SET_TEXT_NO_FIELD`、`:1463 private const val SET_TEXT_NO_FIELD = 0`。
  只认 rootInActiveWindow，遇 WebView 自绘输入框无第二条路（ADBKeyboard 式广播 IME 是补法）。
- **status 缺 shell 字段**：`Executor.kt:352-364` 只有 ok/ver/acc/svc/scr/bat/loc/loop/opt/queue/guard 十个键；
  `shellOk` 只在 `SelfAdbPlan.kt:371` 与 `Executor.kt:1191` 的 sh 输出里用。
- 命令总数：Commands.kt 实测 82 个 parse 分支（从 `:494 "ping"` 起）。

## 四、副屏（overlay display）实测结论

魅族 MEIZU_22（Android 16）实测，结论是「能跑但不算互不影响」：

- 开启：`settings put global overlay_display_devices 800x1280/213` → `dumpsys display` 出现
  `DisplayViewport{type=VIRTUAL, valid=true, isActive=true, displayId=27, uniqueId='overlay:1', logicalFrame=Rect(0, 0 - 800, 1280)}`，
  DisplayDeviceInfo 名 `"叠加视图 #1"`。
- 独立跑 App：`am start --display 27 -n com.android.settings/.Settings` → `am stack list` 出
  `RootTask id=741 bounds=[0,0][800,1280] displayId=27`，主屏 displayId=0 的 launcher 照常。
- 截图：`screencap -p -d 27` 报 `Display Id '27' is not valid`；要用 SurfaceFlinger 的 id
  `11529215048587954842`（`dumpsys SurfaceFlinger --display-id` 查）才成功。
- 注入：`input -d 27 swipe 400 900 400 300 300` exit=0，截图 md5 从 `98b9df19c0272b2629f13c49a77e05be`
  变 `18d9c68289c35b0adb0eab53bdea3fb1` → 真生效。**注意 input 用 27，screencap 用那个大数，编号体系不同。**
- 致命缺点：副屏是叠在物理屏上的一块**半透明浮窗**，主屏截图能看到标题「叠加视图 #1: 800x1280, 213」挡住一块区域（可拖动）。
- freeform 小窗无效：`am start --windowingMode 5` 后 `am stack list` 里仍是 `mWindowingMode=fullscreen`、bounds=[0,0][1200,2670]。

## 五、不建议抄的（来自参考会话）

- minicap / minitouch：每个 Android 版本都可能坏。
- Appium 全栈：太重。
- MediaProjection 免授权绕过：违规且不可靠。
- 「挂 mediaPlayback 保活」：`AndroidManifest.xml:9-12` 注释说明 specialUse 是刻意选的 ——
  Android 16 的 6 小时前台服务超时只覆盖 dataSync / mediaProcessing，specialUse 不受限；
  挂 mediaPlayback 对存活零收益，还要付三处代价（假播放被判异常、抢音频焦点打断音乐、锁屏假卡片）。

## 六、换机适配（结论）

- 全仓零 `if (机型 == ...)`，`Build.MODEL` 只用于上报。
- minSdk = 31 是硬门槛（`accessibility_service_config.xml:30-31` 用 canTakeScreenshot）。
- 换机只需重做一次「装机仪式」：装包 → 无障碍 → 设备管理器 → 电池白名单 → 通知使用权 →
  电脑 adb pair → `pm grant` 四条（全限定名）→ `shkey import` → 开无线调试。
- 新机开箱抢 Device Owner 能解锁 setKeyguardDisabled 与静默装卸。
