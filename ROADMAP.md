# phone-agent 路线图（2026-11-25 老板拍板）

## 产品定位
一台**自己的手机**上的远程操控代理。先把自己的魅族（Android 16）做到能力上限，再向下兼容妈妈那台联想平板（Android 12）。

## 界面架构（老板 2026-11-25 明确）
分两层，不再混在一个页面里：

| 层 | 谁看 | 内容 |
|----|------|------|
| **配置界面** | 普通用户 / 妈妈 | Relay 地址、Agent ID、Secret、启动/停止、开机自启、常亮开关、状态灯（在线/无障碍/电量）。**只放她需要点的东西，其余全部隐藏。** |
| **调试界面** | 我自己 | 独立窗口/独立 Activity：全部命令面板、原始 status JSON、日志、特权权限矩阵、adb 信息、测试按钮。**普通用户进不去。** |

调试界面入口：配置界面长按标题 5 次，或 `am start` 指定 Activity，不放在明面上。

## 版本计划

### 已交付（2026-11-25）
| 版本 | 内容 |
|------|------|
| v0.4.2 | `a11y status`、`call speaker`、`files find/df/pull`、装包后重建无障碍 |
| v0.4.3 | 自愈逻辑挪到 autoStart 门控之前；`a11y rescue` 强制重连 |
| v0.4.4 | 启动即自检（进程一定启动，广播不一定到）；单修复锁 |
| v0.5.0 | 自连 ADB：vendoring libadb（41 个 .java，零第三方依赖，bouncycastle 换 JCE） |
| v0.5.1 | `shkey import <pemUrl> [pubUrl]`（用已授权钥匙灌进 App）、`shkey show` |
| v0.5.2 | `shell:` 前缀修正；`shkey adopt` 证伪（user 版进不去 /data/misc/adb） |
| v0.5.4 | **PTY 通道**：这版 Flyme 的 raw shell 全死，只有 `shell:` 空命令能开；哨兵 `echo "__RC=$?"` |
| v0.5.5 | 打开即自启 + uid 2000 守护脚本（活过 force-stop）+ `watchdog status/arm/disarm` |
| v0.5.6 | relay `stop` 必须清 autoStart，否则守护把它拉回来 |
| v0.5.7 | relay/secret/agentId 编译期内置（走 `local.properties`，public 仓库不进库）；`DebugActivity` 抽出 |
| v0.5.8 | 配置页瘦身（状态卡 + 单按钮 + 一键设置权限向导 + 高级设置默认收起）；日志三处封顶（内存 400 行 / 守护日志 64KB 保 200 行 / APK 残留 24h）；`logs size`、`logs trim` |
| v0.5.9 | Android 16 下 `logs` 改走 uid 2000（App 自己读必弹系统授权框，每次都要重弹） |
| v0.6.0 | `tree` 读活动窗口而非旧快照（`WindowPicker` 合并多窗口 + `TreeSettle` 重取） |
| v0.6.1 | 无障碍配置补订阅 `typeWindowContentChanged` 等六类事件（框架靠订阅决定要不要维护内容缓存）；新增 `tree uia`（uid 2000 跑 uiautomator dump）作独立通道 |
| v0.6.2 | 配置页三盏状态灯：24dp 大灯三态（运行中/部分异常/已停止）+ 只列不对的项 + 无障碍/电池/自连ADB 三盏小灯；四项现探不缓存，前台每 3 秒重跑 |
| v0.6.3 | 去掉「开机自动启动」复选框（生命周期本来就跟启动/停止按钮走）；守护两坑：disarm 改 `pkill -9` 重试三次、arm 比脚本版本号自动换新 |
| v0.6.4 | mDNS 只认本机地址（原来会把同局域网另一台设备的端口当自己的）+ `shkey import` 不再强转 `RSAPrivateCrtKey`（Android 12 上必失败） |
| v0.6.5 | 停止后不再被守护每 60 秒拉回前台：`/sdcard/.phoneagent-stop` 标记 + 守护改 `am start-foreground-service`（失败才回落到 `am start`） |
| v0.7.0 | 视频转音乐：进程内 MediaExtractor + MediaCodec + vendored jump3r（71 个 .java），`mp3 scan` / `mp3 run [apply] [force]` + 配置页「视频转音乐」入口；平板实测 5–7 倍速 |
| v0.7.1 | 修 v0.7.0 转出来是静音：`lame_encode_buffer_int` 要 ±2^31 量级，之前递 ±32768 小 65536 倍（-91 dB → -10.6 dB） |
| v0.7.2 | LAME 编码器改成拿到 `outputFormat` 再惰性建（HE-AAC 轨道头 22050/输出 44100 不再被误判失败）；PCM float/short 口径以解码器为准；环绕声明确拒绝；单文件超时 10→30 分钟 |
| v0.7.3 | 「视频转音乐」页回到前台自动重扫（妈妈先开页面再放文件就看不到新视频）+ 手动「刷新」兜底 |
| v0.8.0 | 模块化地基（`FeatureModule` + `FeatureRegistry`，加功能只新增一个 `Feat*.kt` + 注册表一行）+ 首批 7 个功能：`where` / `status` 加 `shell` 键 / `autowake` / `media` / `battery` / `notify` / `wm`（带自动过期保险丝） |
| v0.8.1 | 修 v0.8.0 真机三坑：`wm` 漏了 "wm " 前缀（所有 apply 全 127）、`notify` 只认开头 apply、`battery` 文案不说实话 |
| v0.8.2 | `wm` 读命令报当前生效值（Override 优先）而不是面板出厂值，保险丝基准仍取 Physical；有 Override 时输出 `(override; physical WxH/D)` |
| v0.8.3 | `tree` 的 `flags` 末尾加 `z`＝这条 rect 点不了（`right<=left` 或 `bottom<=top`，即 `click` 拒绝的那种）。只标不滤：文字仍是「屏上有什么」的证据，忙页面里它们占文本节点多数（糖豆 76%、知乎 12%）。`z` 排最后，老字母位置不动 |
| v0.8.4 | `click` 对 `z` 节点不再只会说「没坐标」：`ACTION_CLICK` 失败且自身 rect 不可用时向上找最近「可点 + rect 可用」的祖先，找到就把祖先**点名**（`nearest clickable ancestor is <class> "<text>" at <l,t,r,b> — add \`up\` to tap it`）但仍 exit 5；加 `up` 开关（`click up SEL`，与 `apply` 同为首位开关、顺序任意）才真点祖先中心，输出 `clicked via=ancestor … ancestor=<class> <l,t,r,b>`。不自动点：向上跨语义边界（行里的「删除」点了是开详情不是删除），猜错在无人看管的手机上代价不可逆 |
| v0.8.5 | 给 v0.8.4 的「向上找可点祖先」加几何约束：候选祖先必须与命中节点**横向且纵向都相交**（rect 先归一化，`aLeft <= bRight && bLeft <= aRight && aTop <= bBottom && bTop <= aBottom`，缺 rect 永不算相交）。原因：糖豆网页 113 个带文本 `z` 节点全挑到同一个屏外「注册」按钮 `1060,402,1153,451`（WebView 把它挤到同一行），25 个会点错。父子天然几何包含，真持有标签的祖先必然与之共享像素；加约束后 27/113，全部落到真持有标签的整宽播放条 `0,2536,1201,2590`。找不到相交祖先 → 回到 v0.8.3 原文案不给 hint；`up` 且无相交祖先 → 仍 `no clickable ancestor for: …` exit 5 |
| v0.9.0 | 「摄像头看图」页：Camera2 自开相机取帧（截屏拿到的是整页画面，半分辨率、带状态行和键盘、还随机型可能是全黑），预览与 JPEG 同 session 双 target，`CamPlan.pickSize` 从 SCAM 挑近 1280x720，按 300ms 等键盘收起后 still capture → 缩到 ≤1280 重压 quality 80 → 像素转正 → `PUT /agent/blob/` + `POST /agent/note`（`kind=look`，带 text/blob/w/h），页面自建 `RcClient` 不依赖服务；配置页加按钮，`UiText.clickable` 8→9 |
| v0.9.1 | 「摄像头看图」锁竖屏（`android:screenOrientation="portrait"`）。这页是竖着拿手机对准货架用的：横过来输入框被压到一行、预览只剩 575 px 高；更实际的是旋转会重建 Activity，相机重开、在飞的 still 跟着旧实例一起死。全 App 只有这一页不跟系统旋转 |

### v0.5.7 后半 —— 保活 + 本地 OCR（未做）
- 静音音频循环保活（仅后台任务时开，可关）
- ML Kit 本地文字识别：`ocr` 命令，截图直接出文字，省掉把 base64 传回模型的成本

### 界面重构（已完成 3 步）
- v0.5.7：拆 `MainActivity`（配置，妈妈看的）+ `DebugActivity`（调试，我看的，exported=false，5 连点版本号进入）
- v0.5.8：配置页瘦身 + 一键设置权限向导（每点一次做下一件缺的事）
- v0.6.2：状态灯（一眼知道好坏）

## 不抄 dsh-go 的部分
宠物、酒馆、分享回放、悬浮条美学、技能自动草稿、不打扰模式（虚拟屏在 Android 12 上没有，且妈妈平板不需要）。
