package net.de5.phoneagent

import android.content.Context
import java.io.File

/**
 * 停止标记：**App 自己写、uid 2000 的守护脚本读**（v0.6.5-pad12）。
 *
 * 存在的理由是一个真机报障：老板点了「停止」，界面却每 60 秒自己弹回前台。
 * 守护脚本能停，靠的是两个标记——`/data/local/tmp/phoneagent-guard.off`（脚本自己
 * 查）和进程表（`pkill`）——而**这两个都只有自连 ADB 能写**。走蜂窝时
 * `adb_wifi_enabled=0`、mDNS 发现不到端口，于是 App 写不了 off、也 kill 不了，
 * 脚本照旧每轮发现服务没了就 `am start`，界面必然弹前台。
 *
 * 所以标记换到 `/sdcard`：App 有「所有文件访问」（MANAGE_EXTERNAL_STORAGE）就能写
 * 根目录，shell 在 sdcard_rw 组里就能读，**完全不需要 ADB**。写不下就是写不下，
 * 不假装成功（返回 false 并记一行日志），因为这条链一旦假成功，老板会继续被弹窗。
 *
 * 不变量：**存在即停止**。内容无关紧要，只写一个字节，方便 `[ -f ]` 判断。
 */
internal object StopFlag {

    /** 文件名（不带目录）。脚本那边用 [PATH] 的完整字面量。 */
    const val FILE_NAME = ".phoneagent-stop"

    /** 绝对路径：脚本读的就是这一个，两边必须是同一个字面量。 */
    const val PATH = "/sdcard/" + FILE_NAME

    /** 标记内容。一个字节足够，存在与否才是信息。 */
    private const val CONTENT = "1"

    fun file(): File = File(PATH)

    /** 存在即停止。读失败（权限、IO）一律当成「没设」，宁可多弹一次也不谎报停止。 */
    fun isSet(file: File = file()): Boolean = try {
        file.isFile
    } catch (t: Throwable) {
        false
    }

    /**
     * 写标记。**任何失败都不抛**：返回 false 由调用方决定怎么告诉操作员。
     *
     * 幂等：已经存在时再写一次也是 true（覆盖写同样一个字节）。
     */
    fun set(file: File = file()): Boolean = try {
        file.parentFile?.mkdirs()
        file.writeText(CONTENT)
        true
    } catch (t: Throwable) {
        false
    }

    /** 删标记。本来就不存在也算成功（幂等）；删不掉返回 false。 */
    fun clear(file: File = file()): Boolean = try {
        !file.exists() || file.delete()
    } catch (t: Throwable) {
        false
    }

    /**
     * 按停止时调。写不下要说出来——不然「界面还是自己弹」会被当成新 bug 重查一遍。
     */
    fun set(context: Context): Boolean {
        val ok = set()
        if (!ok) {
            AgentLog.add("stop", "停止标记没落下：/sdcard 写不了（需要「所有文件访问」权限），守护可能还会把界面拉起来")
        }
        return ok
    }

    /** 按启动时调。删不掉不算致命：下一次 arm 会再清一次（见 [clearForArm]）。 */
    fun clear(context: Context) {
        if (!clear()) AgentLog.add("stop", "停止标记删不掉：/sdcard 写不了，下次 arm 会再清一次")
    }

    /**
     * arm 的第一件事：清掉 App 侧的标记，返回**是否真的清掉了**（给日志用）。
     *
     * 不清会怎样：上次按停止留下的标记让新起的脚本第一轮就 `exit 0`，表现为
     * 「arm 说成功、守护秒退」，比不起来更难查。
     */
    fun clearForArm(file: File = file()): Boolean = if (isSet(file)) clear(file) else false
}
