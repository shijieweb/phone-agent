package net.de5.phoneagent

import android.os.StatFs
import java.io.File

/**
 * The Android side of `files` (SPEC.md section 3.7).
 *
 * Everything that decides *what path is legal* lives in [FileGuard]; this file
 * only touches the disk and turns a failure into a sentence with the right exit
 * code. Keeping the split means the guard's rules are unit-tested on the JVM
 * while this file stays thin enough to read in one screen.
 */
internal object FileOps {

    /**
     * How many directory entries one `files find` may visit.
     *
     * A cap on *entries visited*, not on matches: a search that returns nothing
     * still has to finish, and an uncapped walk over a full card is how the
     * relay poll starts timing out.
     */
    const val SCAN_MAX_FILES = 60_000

    class Result(val exit: Int, val stdout: String, val stderr: String)

    /**
     * A file staged for upload: either [bytes] plus the blob name it will take,
     * or a finished refusal in [message].
     *
     * Reading and uploading are separated because the upload needs the relay
     * client, which only the executor has — and because a file that is too large
     * must be refused *before* 16 MB is pulled into memory.
     */
    class Payload(
        val exit: Int,
        val bytes: ByteArray?,
        val path: String,
        val name: String,
        val message: String
    )

    private fun ok(text: String) = Result(EXIT_OK, text, "")

    private fun fail(exit: Int, message: String) = Result(exit, "", message)

    /**
     * Resolve the operator's path, or explain why not.
     *
     * A refusal is EXIT_USAGE: the caller typed something outside the sandbox,
     * which is a usage mistake, not a permission problem on the device.
     */
    private fun resolve(input: String?): File? = FileGuard.resolve(input)?.let { File(it) }

    private fun refused(input: String?): Result = fail(
        EXIT_USAGE,
        "路径不可用: " + (input ?: "(空)") + "（只允许 /sdcard 内的路径）"
    )

    /**
     * List one directory.
     *
     * `listFiles` returning null is the framework saying "not readable" — on
     * Android 11+ that is exactly what scoped storage does to another app's
     * files without MANAGE_EXTERNAL_STORAGE, so the message names that toggle
     * instead of pretending the directory is empty.
     */
    fun list(input: String?, limit: Int): Result {
        val dir = resolve(input) ?: return refused(input)
        if (!dir.exists()) return fail(EXIT_NOT_FOUND, "目录不存在: " + dir.absolutePath)
        if (!dir.isDirectory) return fail(EXIT_NOT_FOUND, "不是目录: " + dir.absolutePath)
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return fail(
            EXIT_PERMISSION,
            "无法读取 " + dir.absolutePath + "：需要「所有文件访问」权限" +
                "（adb shell appops set --uid net.de5.phoneagent MANAGE_EXTERNAL_STORAGE allow）"
        )
        val zone = java.time.ZoneId.systemDefault()
        val rows = ArrayList<FileGuard.Row>(children.size)
        for (child in children) {
            rows.add(
                FileGuard.Row(
                    child.name,
                    child.isDirectory,
                    if (child.isDirectory) 0L else child.length(),
                    child.lastModified()
                )
            )
        }
        return ok(FileGuard.listing(dir.absolutePath, rows, limit, zone))
    }

    /**
     * Print one text file.
     *
     * The size cap is enforced *before* reading: a 2 GB log read into a String
     * would take the agent down, and the operator only wanted the head anyway.
     */
    fun cat(input: String): Result {
        val file = resolve(input) ?: return refused(input)
        if (!file.exists()) return fail(EXIT_NOT_FOUND, "文件不存在: " + file.absolutePath)
        if (file.isDirectory) return fail(EXIT_USAGE, "这是目录，请用 files ls: " + file.absolutePath)
        if (file.length() > FileGuard.CAT_MAX_BYTES) {
            return fail(
                EXIT_USAGE,
                "文件太大: " + FileGuard.formatSize(file.length()) + "（cat 上限 " +
                    FileGuard.formatSize(FileGuard.CAT_MAX_BYTES.toLong()) + "）"
            )
        }
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            return fail(EXIT_PERMISSION, "读取失败: " + describe(t))
        }
        for (b in bytes) {
            if (b == 0.toByte()) {
                return fail(EXIT_USAGE, "看起来是二进制文件（含 NUL 字节），拒绝按文本输出")
            }
        }
        return ok(String(bytes, Charsets.UTF_8))
    }

    /** Size, type and mtime of one entry — the answer to "did the file land?". */
    fun info(input: String): Result {
        val file = resolve(input) ?: return refused(input)
        if (!file.exists()) return fail(EXIT_NOT_FOUND, "不存在: " + file.absolutePath)
        val zone = java.time.ZoneId.systemDefault()
        return ok(
            FileGuard.describe(
                file.absolutePath,
                file.isDirectory,
                if (file.isDirectory) 0L else file.length(),
                file.lastModified(),
                zone
            )
        )
    }

    /**
     * Delete one file or one directory tree.
     *
     * [apply] false is the default because a delete has no undo: the dry run
     * names the exact absolute path and says how much is about to go.
     */
    fun remove(input: String, apply: Boolean): Result {
        val file = resolve(input) ?: return refused(input)
        if (FileGuard.isRoot(file.absolutePath)) {
            return fail(EXIT_USAGE, "拒绝删除根目录 " + file.absolutePath)
        }
        if (!file.exists()) return fail(EXIT_NOT_FOUND, "不存在: " + file.absolutePath)
        val size = if (file.isDirectory) treeSize(file) else file.length()
        val what = if (file.isDirectory) "目录" else "文件"
        if (!apply) {
            return ok(
                "将删除" + what + " " + file.absolutePath + "（" + FileGuard.formatSize(size) +
                    "，dry-run：未删除，确认后执行 files rm apply " + file.absolutePath + "）"
            )
        }
        val gone = try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } catch (t: Throwable) {
            return fail(EXIT_PERMISSION, "删除失败: " + describe(t))
        }
        if (!gone) {
            return fail(
                EXIT_PERMISSION,
                "删除失败: " + file.absolutePath + "（需要「所有文件访问」权限）"
            )
        }
        AgentLog.add("files", "已删除 " + file.absolutePath)
        return ok("已删除" + what + " " + file.absolutePath + "（" + FileGuard.formatSize(size) + "）")
    }

    /** Create a directory and any missing parents; an existing one is success. */
    fun mkdir(input: String): Result {
        val file = resolve(input) ?: return refused(input)
        if (file.isDirectory) return ok("目录已存在: " + file.absolutePath)
        if (file.exists()) return fail(EXIT_USAGE, "同名文件已存在: " + file.absolutePath)
        val made = try {
            file.mkdirs()
        } catch (t: Throwable) {
            return fail(EXIT_PERMISSION, "创建失败: " + describe(t))
        }
        if (!made) return fail(EXIT_PERMISSION, "创建失败: " + file.absolutePath)
        AgentLog.add("files", "已创建目录 " + file.absolutePath)
        return ok("已创建目录: " + file.absolutePath)
    }

    /**
     * Recursive name search under one directory.
     *
     * Three deliberate stops: the walk never enters `Android/data` or
     * `Android/obb` ([FindPlan.skipped], they are unreadable to us and would
     * look like "not there"), never follows a symlink (a loop would hang the
     * agent), and stops after [SCAN_MAX_FILES] entries so one `files find /sdcard
     * mp4` cannot walk a 200 GB card for minutes on a phone that is also
     * answering a relay poll.
     */
    fun find(input: String, keyword: String, limit: Int): Result {
        val root = resolve(input) ?: return refused(input)
        if (!root.exists()) return fail(EXIT_NOT_FOUND, "目录不存在: " + root.absolutePath)
        if (!root.isDirectory) return fail(EXIT_NOT_FOUND, "不是目录: " + root.absolutePath)
        val hits = ArrayList<FindPlan.Hit>()
        var scanned = 0
        var capped = false
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = try {
                current.listFiles()
            } catch (t: Throwable) {
                null
            } ?: continue
            for (child in children) {
                if (scanned >= SCAN_MAX_FILES) {
                    capped = true
                    break
                }
                scanned++
                if (FindPlan.skipped(child.absolutePath)) continue
                val isDir = child.isDirectory
                if (FindPlan.matches(child.name, keyword)) {
                    hits.add(
                        FindPlan.Hit(
                            child.absolutePath,
                            isDir,
                            if (isDir) 0L else child.length()
                        )
                    )
                }
                if (isDir && !isSymlink(child)) stack.addLast(child)
            }
            if (capped) break
        }
        return ok(FindPlan.report(root.absolutePath, keyword, hits, limit, capped))
    }

    /**
     * `files df` — the shared volume's capacity.
     *
     * StatFs on the root itself (not on `/data`) because the operator is asking
     * about the storage they can see in a file manager.
     */
    fun df(): Result {
        val root = File(FileGuard.ROOT)
        return try {
            val stat = StatFs(root.absolutePath)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            val used = (total - free).coerceAtLeast(0L)
            ok(DfPlan.report(total, used, free))
        } catch (t: Throwable) {
            fail(EXIT_PERMISSION, DfPlan.failure(describe(t)))
        }
    }

    /**
     * Stage one file for `files pull`.
     *
     * Everything that can refuse is refused here, in the order an operator can
     * act on: wrong path, missing, a directory, too large — then the bytes.
     */
    fun pullPayload(input: String, millis: Long, suffix: String): Payload {
        val file = resolve(input) ?: return Payload(
            EXIT_USAGE, null, input, "",
            "路径不可用: " + input + "（只允许 /sdcard 内的路径）"
        )
        if (!file.exists()) {
            return Payload(EXIT_NOT_FOUND, null, file.absolutePath, "", "文件不存在: " + file.absolutePath)
        }
        if (file.isDirectory) {
            return Payload(
                EXIT_USAGE, null, file.absolutePath, "",
                "这是目录，无法上传: " + file.absolutePath + "（先打包，或改用 files find 定位单个文件）"
            )
        }
        val size = file.length()
        if (size > ShotBlob.MAX_BYTES) {
            return Payload(EXIT_USAGE, null, file.absolutePath, "", PullPlan.tooLarge(size))
        }
        if (size <= 0L) {
            return Payload(EXIT_USAGE, null, file.absolutePath, "", "文件是空的: " + file.absolutePath)
        }
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            return Payload(EXIT_PERMISSION, null, file.absolutePath, "", "读取失败: " + describe(t))
        }
        val name = PullPlan.name(millis, suffix, PullPlan.extensionOf(file.absolutePath))
        return Payload(EXIT_OK, bytes, file.absolutePath, name, "")
    }

    /**
     * True for a symbolic link.
     *
     * The walk must not follow one: `/sdcard` is full of links back into itself
     * (`/sdcard/sdcard`, app-specific aliases), and a cycle would make
     * `files find` run until the scan cap on every single call.
     */
    private fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (t: Throwable) {
        false
    }

    /** Recursive size, for the dry-run sentence; a walk failure just stops counting. */
    private fun treeSize(root: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = try {
                current.listFiles()
            } catch (t: Throwable) {
                null
            }
            if (children == null) {
                total += current.length()
                continue
            }
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }
}
