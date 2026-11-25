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

### v0.5.7 后半 —— 保活 + 本地 OCR（未做）
- 静音音频循环保活（仅后台任务时开，可关）
- ML Kit 本地文字识别：`ocr` 命令，截图直接出文字，省掉把 base64 传回模型的成本

### 界面重构（已完成 3 步）
- v0.5.7：拆 `MainActivity`（配置，妈妈看的）+ `DebugActivity`（调试，我看的，exported=false，5 连点版本号进入）
- v0.5.8：配置页瘦身 + 一键设置权限向导（每点一次做下一件缺的事）
- v0.6.2：状态灯（一眼知道好坏）

## 不抄 dsh-go 的部分
宠物、酒馆、分享回放、悬浮条美学、技能自动草稿、不打扰模式（虚拟屏在 Android 12 上没有，且妈妈平板不需要）。
