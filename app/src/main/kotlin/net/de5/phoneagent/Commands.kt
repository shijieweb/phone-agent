package net.de5.phoneagent

/**
 * Command grammar (SPEC.md section 3) plus the shared exit-code contract.
 *
 * This file deliberately contains **no Android imports**: [Commands.parse] is a
 * pure function, so it can be exercised on a plain JVM (SPEC.md section 10).
 */

private val WHITESPACE = Regex("\\s+")

/** Exit codes, SPEC.md section 3. */
const val EXIT_OK = 0
const val EXIT_USAGE = 2
const val EXIT_NO_ACCESSIBILITY = 3
const val EXIT_TIMEOUT = 4
const val EXIT_NOT_FOUND = 5
const val EXIT_PERMISSION = 6
const val EXIT_INTERRUPTED = 7

/**
 * One parsed command line. [Unknown] and [BadUsage] are part of the model on
 * purpose: the caller still has to report *something* back to the relay, and
 * both of them map to `exit=2`.
 */
sealed class Command {
    object Ping : Command()
    object Status : Command()
    /**
     * `shot [b64] [maxPx] [quality]`.
     *
     * [forceBase64] skips the blob channel and prints base64 on stdout, which is
     * what an operator wants when they are reading the output themselves instead
     * of letting a controller fetch the bytes (SPEC.md section 4).
     */
    data class Shot(
        val maxPx: Int,
        val quality: Int,
        val forceBase64: Boolean = false
    ) : Command()
    data class Tree(val maxNodes: Int) : Command()
    data class Tap(val x: Int, val y: Int) : Command()
    data class LongPress(val x: Int, val y: Int, val ms: Int) : Command()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int) : Command()
    data class Key(val name: String) : Command()
    /**
     * `click [apply] SEL` — element-level click (SPEC.md section 3.1).
     *
     * [apply] is false by default and the action is then a **dry run**: we report
     * which node would be hit and where, and touch nothing. A phone nobody is
     * holding makes a wrong tap expensive, so the destructive half is opt-in.
     */
    data class Click(val selector: Selector, val apply: Boolean = false) : Command()
    /** `longclick [apply] SEL` — element-level long click (SPEC.md section 3.1). */
    data class LongClick(val selector: Selector, val apply: Boolean = false) : Command()
    /** `scroll up|down [n] [SEL]` (SPEC.md section 3.1). */
    data class Scroll(val direction: Direction, val count: Int, val selector: Selector?) : Command()
    /** `ime enter` — send ACTION_IME_ENTER to the focused editable node. */
    object ImeEnter : Command()
    /** `wake` — turn the screen on, try to clear a non-secure keyguard. */
    object Wake : Command()
    /** `text` keeps the rest of the line verbatim, spaces included. */
    data class Text(val value: String) : Command()
    object Apps : Command()
    data class App(val pkg: String) : Command()
    object Loc : Command()
    data class LocLoop(val interval: Int) : Command()
    object LocStop : Command()
    object Stop : Command()

    // ------------------------- privileged / admin surface -------------------

    /** `install URL` — download the APK, then hand it to the package installer. */
    data class Install(val url: String) : Command()
    /** `uninstall PKG` — silently remove another package. */
    data class Uninstall(val pkg: String) : Command()
    /**
     * `settings global|secure|system KEY [VALUE]`.
     *
     * [value] is the rest of the line verbatim (so it may contain spaces); null
     * means "read and report the current value".
     */
    data class Settings(val namespace: String, val key: String, val value: String?) : Command()
    /** `logs [lines]` — a tail of logcat. */
    data class Logs(val lines: Int) : Command()

    /**
     * `confirm [t=SECONDS] TEXT` — click the on-screen button whose label
     * contains TEXT, retrying until [timeoutSeconds] runs out.
     *
     * This is what turns `install` into a hands-free flow: the system installer
     * is a UI like any other, and the operator is not holding the phone.
     */
    data class Confirm(
        val text: String,
        val timeoutSeconds: Int,
        val apply: Boolean = false
    ) : Command()
    /** `admin status` — Device Owner / device-admin state. */
    object AdminStatus : Command()
    /** `admin release` — drop Device Owner and remove ourselves as admin. */
    object AdminRelease : Command()
    /**
     * `a11y heal` — run whichever repair the current state needs.
     *
     * It used to mean only "append a missing entry", which answers "条目已存在"
     * and changes nothing on the state that actually breaks a phone after an
     * update (listed, switch on, binding dead).
     */
    object A11yHeal : Command()

    /**
     * `a11y rescue` — release and re-add the binding unconditionally.
     *
     * The difference from [A11yHeal] is intent, not implementation: `heal`
     * inspects first and picks, `rescue` always runs the toggle, because the
     * operator has already read `a11y status` and decided.
     */
    object A11yRescue : Command()

    /**
     * `a11y status` — read-only: master switch, our entry, live binding.
     *
     * `status` already carries one `acc` boolean, which cannot tell "the entry
     * was removed" from "the process died"; this names all three facts and the
     * one repair that fits.
     */
    object A11yStatus : Command()
    /** `lock` — lock the screen now. */
    object Lock : Command()
    /** `reboot` — reboot the device (Device Owner only). */
    object Reboot : Command()
    /** `adbinfo` — read-only wireless-debugging report: IP, port, connect string. */
    object AdbInfo : Command()
    /**
     * `adbheal [t=SECONDS]` — turn wireless debugging on if it is off, wait for
     * the mDNS advertisement, then report exactly what `adbinfo` reports.
     *
     * Idempotent: safe to re-run after every reboot, which is the whole point
     * (the port changes on every boot and pairing survives, so only the port
     * ever needs rediscovering).
     */
    data class AdbHeal(val timeoutSeconds: Int) : Command()

    /**
     * `dl [t=SECONDS] URL [NAME]` — fetch any file into `/sdcard/Movies` so a
     * media player (VLC) can open it (SPEC.md section 3.6).
     *
     * [name] is the rest of the line verbatim; null means "guess from the URL".
     */
    data class Dl(val url: String, val name: String?, val timeoutSeconds: Int) : Command()

    // ------------------------------------------------- file / device surface

    /**
     * `files ls [PATH] [n]` — list one directory (SPEC.md section 3.7).
     *
     * [path] is null for the root. [limit] is capped by [FileGuard.LIMIT_MAX] in
     * the parser, so a listing can never be asked for more rows than the relay
     * can carry.
     */
    data class FilesList(val path: String?, val limit: Int) : Command()

    /** `files cat PATH` — print a text file, capped at [FileGuard.CAT_MAX_BYTES]. */
    data class FilesCat(val path: String) : Command()

    /** `files info PATH` — size, type and modification time of one entry. */
    data class FilesInfo(val path: String) : Command()

    /**
     * `files rm [apply] PATH` — delete one file or directory tree.
     *
     * Dry run by default, like every other destructive verb here: a phone nobody
     * is holding makes a wrong delete unarguable.
     */
    data class FilesRemove(val path: String, val apply: Boolean = false) : Command()

    /** `files mkdir PATH` — create a directory (and its parents). */
    data class FilesMkdir(val path: String) : Command()

    /**
     * `files find PATH KEYWORD [n]` — recursive name search (SPEC.md §3.7).
     *
     * [keyword] is a plain substring, matched case-insensitively: the operator
     * types `mp4`, not a regex, and a regex over a phone's whole storage is a
     * way to make the agent hang.
     */
    data class FilesFind(val path: String, val keyword: String, val limit: Int) : Command()

    /** `files df` — total / used / free on the shared volume. */
    object FilesDf : Command()

    /**
     * `files pull PATH` — upload one file to the relay as a blob.
     *
     * This is the only way a file leaves the phone: the relay carries the bytes
     * and the answer is one JSON line naming what landed.
     */
    data class FilesPull(val path: String) : Command()

    /** `notif [list] [n] [q=TEXT]` — the active notifications (SPEC.md section 3.8). */
    data class NotifList(val limit: Int, val query: String?) : Command()

    /** `say [-l zh|en] [rate=PCT] [pitch=PCT] TEXT` — speak once (SPEC.md §3.9). */
    data class Say(
        val text: String,
        val rate: Int,
        val pitch: Int,
        val lang: String = SayPlan.LANG_DEFAULT
    ) : Command()

    /**
     * `screen stayon [on|off]` — keep the panel awake across reboots (§3.14).
     *
     * [on] is null for the report form: `screen stayon` asks, it does not decide.
     */
    data class ScreenStayOn(val on: Boolean?) : Command()

    /** `screen timeout MS` — one explicit panel timeout, clamped to a usable range. */
    data class ScreenTimeout(val ms: Int) : Command()

    /** `screen on` — light the panel now. */
    object ScreenOn : Command()

    /** `screen off` — darken the panel now (via the device-admin lock). */
    object ScreenOff : Command()

    /** `sms [list] [n]` — the most recent messages (SPEC.md section 3.12). */
    data class SmsList(val limit: Int) : Command()

    /**
     * `sms send [apply] NUMBER TEXT` — send one SMS.
     *
     * Dry run by default: the body goes to a real person, and there is no undo.
     */
    data class SmsSend(val number: String, val text: String, val apply: Boolean = false) : Command()

    /** `anim [get]` — read the three animation scales (SPEC.md section 3.11). */
    object AnimGet : Command()

    /** `anim set on|off|SCALE` — write all three. */
    data class AnimSet(val value: String) : Command()

    /** `anim save` — remember the current three values in prefs. */
    object AnimSave : Command()

    /** `anim restore` — put the remembered values back. */
    object AnimRestore : Command()

    /**
     * `call [apply] NUMBER` — open the dialer on a number (SPEC.md section 3.13).
     *
     * Dry run by default: a wrong number rings a stranger's phone.
     */
    data class CallDial(val number: String, val apply: Boolean = false) : Command()

    /** `call answer` — accept the ringing call (needs ANSWER_PHONE_CALLS). */
    object CallAnswer : Command()

    /** `call hangup` — end the current call (needs ANSWER_PHONE_CALLS). */
    object CallHangup : Command()

    /** `call state` — idle / ringing / off-hook. */
    object CallState : Command()

    /**
     * `call speaker [on|off]` — read or set the speakerphone (SPEC.md §3.13).
     *
     * [on] is null for the report form. Allowed outside a call too, because an
     * operator usually wants the route ready *before* the call connects; the
     * answer then says which state the phone is in.
     */
    data class CallSpeaker(val on: Boolean?) : Command()

    // ------------------------------------- controller built-in protocol (#rc:)

    /**
     * `#rc: shot {"width":..,"quality":..}` — the screenshot contract of the
     * Windows controller layer, which reads the `name` field of our one-line
     * JSON and fetches the bytes over HTTP (SPEC.md section 4).
     *
     * Unlike [Shot] there is **no base64 fallback**: the controller cannot parse
     * base64, so a blob failure is reported as a JSON error instead.
     */
    data class RcShot(val maxPx: Int, val quality: Int) : Command()

    /** A `#rc:` verb this build does not implement; never runs as a shell command. */
    data class RcUnknown(val verb: String) : Command()

    /** A `#rc:` line we cannot parse at all (missing verb, malformed JSON). */
    data class RcBadPayload(val detail: String) : Command()

    /** The verb is not part of the grammar. */
    data class Unknown(val name: String) : Command()
    /** The verb is known but the arguments cannot be used. */
    data class BadUsage(val detail: String) : Command()
}

/** Scroll direction for `scroll up|down`. */
enum class Direction { UP, DOWN }

object Commands {

    const val SHOT_MAXPX_DEFAULT = 720
    const val SHOT_MAXPX_MIN = 320
    const val SHOT_MAXPX_MAX = 1440

    const val SHOT_QUALITY_DEFAULT = 55
    const val SHOT_QUALITY_MIN = 20
    const val SHOT_QUALITY_MAX = 90

    const val TREE_MAXNODES_DEFAULT = 400
    const val TREE_MAXNODES_MAX = 800

    const val LONG_MS_DEFAULT = 600
    const val LONG_MS_MAX = 5000

    const val SWIPE_MS_DEFAULT = 300
    const val SWIPE_MS_MAX = 3000

    const val LOCLOOP_MIN_SECONDS = 30
    const val LOCLOOP_MAX_SECONDS = 3600

    /** Shared shape of every `settings` usage error, so the text never drifts. */
    private val SETTINGS_USAGE = Command.BadUsage("usage: settings [get|put] global|secure|system KEY [VALUE]")

    /** `scroll up|down [n] [SEL]`: default 1, cap 20 (SPEC.md section 3.1). */
    const val SCROLL_COUNT_DEFAULT = 1
    const val SCROLL_COUNT_MAX = 20

    /** `logs [lines]`: 120 by default, never more than 500 (logcat is chatty). */
    /** `confirm` waits this long by default before giving up. */
    const val CONFIRM_TIMEOUT_DEFAULT = 15
    const val CONFIRM_TIMEOUT_MAX = 120

    const val LOGS_LINES_DEFAULT = 120
    const val LOGS_LINES_MAX = 500

    /** `adbheal [t=SECONDS]`: how long to wait for the port, default 20. */
    const val ADBHEAL_TIMEOUT_DEFAULT = 20
    const val ADBHEAL_TIMEOUT_MAX = 60

    /** Shared shape of every `dl` usage error. */
    private val DL_USAGE = Command.BadUsage("usage: dl [t=SECONDS] URL [NAME]")

    /** Shared shapes of the new verb usage errors, so the text never drifts. */
    private val FILES_USAGE = Command.BadUsage(
        "usage: files ls [PATH] [n] | files cat PATH | files info PATH | " +
            "files rm [apply] PATH | files mkdir PATH | files find PATH KEYWORD [n] | " +
            "files df | files pull PATH"
    )
    private val NOTIF_USAGE = Command.BadUsage("usage: notif [list] [n] [q=TEXT]")
    private val SAY_USAGE = Command.BadUsage(SayPlan.USAGE)
    private val SCREEN_USAGE = Command.BadUsage(ScreenPlan.USAGE)
    private val SMS_USAGE = Command.BadUsage(SmsPlan.USAGE)
    private val ANIM_USAGE = Command.BadUsage(AnimPlan.USAGE)
    private val CALL_USAGE = Command.BadUsage(CallPlan.USAGE)

    /** Shared shapes of the element-verb usage errors, so the text never drifts. */
    private val SHOT_USAGE = "usage: shot [b64] [maxPx] [quality]"
    private val CLICK_USAGE = "usage: click [apply] SEL"
    private val LONGCLICK_USAGE = "usage: longclick [apply] SEL"
    private val CONFIRM_USAGE = "usage: confirm [apply] [t=SECONDS] TEXT"

    private val KEY_NAMES = setOf("back", "home", "recents", "notifications", "quicksettings")

    /**
     * Split one command line. Never throws: every failure path returns
     * [Command.Unknown] or [Command.BadUsage], both of which the executor
     * reports as `exit=2`.
     */
    fun parse(line: String): Command {
        // The controller's built-in action protocol is a different grammar
        // entirely, so it is split off before the shell grammar sees the line:
        // a "#rc:" verb must never fall through to "unknown command".
        if (isRcLine(line)) {
            return when (val rc = parseRcLine(line)) {
                is RcLine.Shot -> Command.RcShot(rc.maxPx, rc.quality)
                is RcLine.Unknown -> Command.RcUnknown(rc.verb)
                is RcLine.Bad -> Command.RcBadPayload(rc.detail)
            }
        }

        val raw = line.trim()
        if (raw.isEmpty()) return Command.Unknown("")

        val cut = firstWhitespace(raw)
        val head = (if (cut < 0) raw else raw.substring(0, cut)).lowercase()
        val rest = if (cut < 0) "" else raw.substring(cut)

        return when (head) {
            "ping" -> Command.Ping

            "status" -> Command.Status

            "shot" -> {
                val args = splitArgs(rest)
                // "b64" is an optional leading flag, so "shot b64 1080 80" and
                // "shot 1080 80" both work and the positional meaning of the
                // numbers never changes.
                val forceBase64 = args.isNotEmpty() && args[0].equals("b64", ignoreCase = true)
                val offset = if (forceBase64) 1 else 0
                val maxPx = optionalInt(args, offset, SHOT_MAXPX_DEFAULT)
                    ?: return Command.BadUsage(SHOT_USAGE)
                val quality = optionalInt(args, offset + 1, SHOT_QUALITY_DEFAULT)
                    ?: return Command.BadUsage(SHOT_USAGE)
                Command.Shot(
                    maxPx.coerceIn(SHOT_MAXPX_MIN, SHOT_MAXPX_MAX),
                    quality.coerceIn(SHOT_QUALITY_MIN, SHOT_QUALITY_MAX),
                    forceBase64
                )
            }

            "tree" -> {
                val args = splitArgs(rest)
                val maxNodes = optionalInt(args, 0, TREE_MAXNODES_DEFAULT)
                    ?: return Command.BadUsage("usage: tree [maxNodes]")
                Command.Tree(maxNodes.coerceIn(1, TREE_MAXNODES_MAX))
            }

            "tap" -> {
                val args = splitArgs(rest)
                if (args.size < 2) return Command.BadUsage("usage: tap X Y")
                val x = args[0].toIntOrNull() ?: return Command.BadUsage("usage: tap X Y")
                val y = args[1].toIntOrNull() ?: return Command.BadUsage("usage: tap X Y")
                Command.Tap(x, y)
            }

            "long" -> {
                val args = splitArgs(rest)
                if (args.size < 2) return Command.BadUsage("usage: long X Y [ms]")
                val x = args[0].toIntOrNull() ?: return Command.BadUsage("usage: long X Y [ms]")
                val y = args[1].toIntOrNull() ?: return Command.BadUsage("usage: long X Y [ms]")
                val ms = optionalInt(args, 2, LONG_MS_DEFAULT)
                    ?: return Command.BadUsage("usage: long X Y [ms]")
                Command.LongPress(x, y, ms.coerceIn(1, LONG_MS_MAX))
            }

            "swipe" -> {
                val args = splitArgs(rest)
                if (args.size < 4) return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                val x1 = args[0].toIntOrNull() ?: return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                val y1 = args[1].toIntOrNull() ?: return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                val x2 = args[2].toIntOrNull() ?: return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                val y2 = args[3].toIntOrNull() ?: return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                val ms = optionalInt(args, 4, SWIPE_MS_DEFAULT)
                    ?: return Command.BadUsage("usage: swipe X1 Y1 X2 Y2 [ms]")
                Command.Swipe(x1, y1, x2, y2, ms.coerceIn(1, SWIPE_MS_MAX))
            }

            "key" -> {
                val args = splitArgs(rest)
                if (args.isEmpty()) {
                    return Command.BadUsage("usage: key back|home|recents|notifications|quicksettings")
                }
                val name = args[0].lowercase()
                if (!KEY_NAMES.contains(name)) return Command.BadUsage("unknown key: $name")
                Command.Key(name)
            }

            "click" -> {
                // SPEC.md section 3.1: the selector is the whole rest of the line,
                // verbatim, with only the separating whitespace dropped.
                val parsed = parseElementVerb(rest)
                when (parsed) {
                    null -> Command.BadUsage(CLICK_USAGE)
                    else -> Command.Click(parsed.selector, parsed.apply)
                }
            }

            "longclick" -> {
                val parsed = parseElementVerb(rest)
                when (parsed) {
                    null -> Command.BadUsage(LONGCLICK_USAGE)
                    else -> Command.LongClick(parsed.selector, parsed.apply)
                }
            }

            "scroll" -> parseScroll(rest)

            "ime" -> {
                val args = splitArgs(rest)
                if (args.isEmpty() || !args[0].equals("enter", ignoreCase = true)) {
                    return Command.BadUsage("usage: ime enter")
                }
                Command.ImeEnter
            }

            "wake" -> Command.Wake

            "text" -> {
                // Rest of the line, verbatim: only the separator whitespace is
                // dropped, inner (and trailing) spaces are part of the payload.
                val value = dropLeadingWhitespace(rest)
                if (value.isEmpty()) return Command.BadUsage("usage: text STRING")
                Command.Text(value)
            }

            "apps" -> Command.Apps

            "app" -> {
                val args = splitArgs(rest)
                if (args.isEmpty()) return Command.BadUsage("usage: app PACKAGE")
                Command.App(args[0])
            }

            "loc" -> Command.Loc

            "locloop" -> {
                val args = splitArgs(rest)
                if (args.isEmpty()) return Command.BadUsage("usage: locloop INTERVAL")
                val interval = args[0].toIntOrNull() ?: return Command.BadUsage("usage: locloop INTERVAL")
                Command.LocLoop(interval.coerceIn(LOCLOOP_MIN_SECONDS, LOCLOOP_MAX_SECONDS))
            }

            "locstop" -> Command.LocStop

            "install" -> {
                // The URL is the whole rest of the line: query strings contain
                // characters we must not split on, only the separator is dropped.
                val url = dropLeadingWhitespace(rest).trim()
                if (url.isEmpty()) return Command.BadUsage("usage: install URL")
                val lower = url.lowercase()
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    return Command.BadUsage("usage: install URL (http:// or https://)")
                }
                Command.Install(url)
            }

            "uninstall" -> {
                // Exactly one token: a second word is a typo, and silently
                // ignoring it would uninstall the wrong package on a phone
                // nobody can watch.
                val args = splitArgs(rest)
                if (args.size != 1 || !isPackageName(args[0])) {
                    return Command.BadUsage("usage: uninstall PACKAGE")
                }
                Command.Uninstall(args[0])
            }

            "settings" -> parseSettings(rest)

            "confirm" -> parseConfirm(rest)

            "logs" -> {
                val args = splitArgs(rest)
                val lines = optionalInt(args, 0, LOGS_LINES_DEFAULT)
                    ?: return Command.BadUsage("usage: logs [lines]")
                Command.Logs(lines.coerceIn(1, LOGS_LINES_MAX))
            }

            "admin" -> {
                when (splitArgs(rest).firstOrNull()?.lowercase()) {
                    "status" -> Command.AdminStatus
                    "release" -> Command.AdminRelease
                    else -> Command.BadUsage("usage: admin status|release")
                }
            }

            "a11y" -> {
                val parts = splitArgs(rest)
                if (parts.size != 1) {
                    Command.BadUsage(A11yPlan.USAGE)
                } else {
                    when (parts[0].lowercase()) {
                        "status" -> Command.A11yStatus
                        "heal" -> Command.A11yHeal
                        "rescue" -> Command.A11yRescue
                        else -> Command.BadUsage(A11yPlan.USAGE)
                    }
                }
            }

            "lock" -> Command.Lock

            "reboot" -> Command.Reboot

            "adbinfo" -> {
                if (splitArgs(rest).isNotEmpty()) {
                    Command.BadUsage("usage: adbinfo")
                } else {
                    Command.AdbInfo
                }
            }

            "adbheal" -> parseAdbHeal(rest)

            "dl" -> parseDl(rest)

            "files" -> parseFiles(rest)

            "notif" -> parseNotif(rest)

            "say" -> parseSay(rest)

            "screen" -> parseScreen(rest)

            "sms" -> parseSms(rest)

            "anim" -> parseAnim(rest)

            "call" -> parseCall(rest)

            "stop" -> Command.Stop

            else -> Command.Unknown(head)
        }
    }

    /**
     * `scroll up|down [n] [SEL]`.
     *
     * Positional, like the rest of the grammar: the first token is the
     * direction, then an optional count, then everything left is the selector —
     * verbatim, so `scroll down 3 list item` targets "list item".
     *
     * Ambiguity note: `scroll down 3` is a count because `3` parses as an
     * integer. To scroll once on an element literally called "3", write a kind
     * prefix (`scroll down text:3`), which cannot be mistaken for a count.
     */
    private fun parseScroll(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return Command.BadUsage("usage: scroll up|down [n] [SEL]")

        val direction = when (tokens[0].text.lowercase()) {
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            else -> return Command.BadUsage("usage: scroll up|down [n] [SEL]")
        }

        var index = 1
        var count = SCROLL_COUNT_DEFAULT
        if (index < tokens.size) {
            val parsed = tokens[index].text.toIntOrNull()
            if (parsed != null) {
                count = parsed.coerceIn(1, SCROLL_COUNT_MAX)
                index++
            }
        }

        // Slice the selector out of the original string so its internal spacing
        // survives verbatim, exactly like `text` / `click` (SPEC.md section 3.1).
        val selector = if (index < tokens.size) {
            val from = tokens[index].start
            val raw = rest.substring(from)
            if (raw.isBlank()) null else Selector.parse(raw)
        } else {
            null
        }

        return Command.Scroll(direction, count, selector)
    }

    /**
     * `settings [get|put] global|secure|system KEY [VALUE]`.
     *
     * Both spellings work: the explicit `get`/`put` verb (what an operator
     * types, mirroring the adb `settings` command they already know) and the
     * bare form where the presence of a value decides read vs write.
     *
     * Split with [tokenSpans] so the value keeps its inner spacing verbatim,
     * exactly like `text` / `click` — a setting value such as
     * `"a  b"` must not be re-joined from tokens.
     */
    private fun parseSettings(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return SETTINGS_USAGE

        // Drop an explicit verb first; what follows is the bare grammar.
        var index = 0
        var forceRead = false
        when (tokens[0].text.lowercase()) {
            "get" -> {
                forceRead = true
                index = 1
            }
            "put" -> index = 1
        }
        if (tokens.size - index < 2) return SETTINGS_USAGE

        val namespace = tokens[index].text.lowercase()
        if (namespace != "global" && namespace != "secure" && namespace != "system") {
            return SETTINGS_USAGE
        }
        val key = tokens[index + 1].text
        if (key.isEmpty()) return SETTINGS_USAGE

        if (forceRead) return Command.Settings(namespace, key, null)
        val valueIndex = index + 2
        val value = if (valueIndex < tokens.size) {
            val raw = rest.substring(tokens[valueIndex].start)
            if (raw.isBlank()) null else raw
        } else {
            null
        }
        return Command.Settings(namespace, key, value)
    }

    /**
     * `confirm [t=SECONDS] TEXT`.
     *
     * The timeout is optional and recognised only as a leading `t=` token, so a
     * button that legitimately starts with a digit (`confirm 2 个选项`) keeps its
     * text instead of being parsed as a timeout.
     */
    private fun parseConfirm(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return Command.BadUsage(CONFIRM_USAGE)

        // Only *leading* flags are consumed, and only while they really are
        // flags: a button label that starts with a digit ("confirm 2 个选项")
        // therefore keeps its text instead of being read as a timeout.
        var index = 0
        var timeout = CONFIRM_TIMEOUT_DEFAULT
        var apply = false
        while (index < tokens.size) {
            val head = tokens[index].text
            if (head.equals("apply", ignoreCase = true)) {
                apply = true
                index++
                continue
            }
            if (head.length > 2 && head.startsWith("t=", ignoreCase = true)) {
                val parsed = head.substring(2).toIntOrNull()
                    ?: return Command.BadUsage(CONFIRM_USAGE)
                timeout = parsed.coerceIn(1, CONFIRM_TIMEOUT_MAX)
                index++
                continue
            }
            break
        }
        if (index >= tokens.size) return Command.BadUsage(CONFIRM_USAGE)

        // The label keeps its inner spacing: it is matched by substring, and
        // re-joining tokens would collapse a two-space button label.
        val text = rest.substring(tokens[index].start).trim()
        if (text.isEmpty()) return Command.BadUsage(CONFIRM_USAGE)
        return Command.Confirm(text, timeout, apply)
    }

    /** One element verb's arguments: the selector plus whether the tap is real. */
    private class ParsedElement(val selector: Selector, val apply: Boolean)

    /**
     * `click [apply] SEL` / `longclick [apply] SEL`.
     *
     * `apply` is recognised only as the *first* token, and the selector is the
     * rest of the line verbatim (inner spaces included), exactly like before. A
     * selector that itself begins with the word stays reachable through a kind
     * prefix: `click text:apply button`.
     */
    private fun parseElementVerb(rest: String): ParsedElement? {
        var body = dropLeadingWhitespace(rest)
        if (body.isBlank()) return null
        var apply = false
        val cut = firstWhitespace(body)
        val head = if (cut < 0) body else body.substring(0, cut)
        if (head.equals("apply", ignoreCase = true)) {
            apply = true
            body = if (cut < 0) "" else dropLeadingWhitespace(body.substring(cut))
            if (body.isBlank()) return null
        }
        return ParsedElement(Selector.parse(body), apply)
    }

    /**
     * `adbheal [t=SECONDS]` — the optional timeout is a leading `t=` token only,
     * same rule as `confirm`, so no argument means the documented default.
     */
    private fun parseAdbHeal(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return Command.AdbHeal(ADBHEAL_TIMEOUT_DEFAULT)

        val head = tokens[0].text
        if (!head.startsWith("t=", ignoreCase = true)) {
            return Command.BadUsage("usage: adbheal [t=SECONDS]")
        }
        val parsed = head.substring(2).toIntOrNull()
            ?: return Command.BadUsage("usage: adbheal [t=SECONDS]")
        if (tokens.size > 1) return Command.BadUsage("usage: adbheal [t=SECONDS]")
        return Command.AdbHeal(parsed.coerceIn(1, ADBHEAL_TIMEOUT_MAX))
    }

    /**
     * `dl [t=SECONDS] URL [NAME]`.
     *
     * The URL is taken as one token and never re-joined, because query strings
     * carry the signature of the link. Everything after it is the name
     * verbatim, so a name with spaces still works (the cleaner turns them into
     * underscores). Only the *first* token may be `t=`, exactly like `adbheal`.
     */
    private fun parseDl(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return DL_USAGE

        var index = 0
        var timeout = DownloadPlan.TIMEOUT_DEFAULT
        if (tokens[0].text.startsWith("t=", ignoreCase = true)) {
            val parsed = tokens[0].text.substring(2).toIntOrNull() ?: return DL_USAGE
            timeout = parsed.coerceIn(1, DownloadPlan.TIMEOUT_MAX)
            index = 1
        }
        if (index >= tokens.size) return DL_USAGE

        val url = tokens[index].text
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Command.BadUsage("usage: dl [t=SECONDS] URL [NAME] (http:// or https://)")
        }
        val nameStart = tokens[index].start + tokens[index].text.length
        val name = dropLeadingWhitespace(rest.substring(nameStart)).trim()
        return Command.Dl(url, if (name.isEmpty()) null else name, timeout)
    }


    /**
     * `files ls [PATH] [n] | files cat PATH | files info PATH | files rm [apply] PATH |
     * files mkdir PATH` (SPEC.md section 3.7).
     *
     * The path is always the rest of the line verbatim, because real names
     * contain spaces ("My Documents"). Only `ls` takes a trailing count, and it
     * is recognised only as a *pure integer last token* — a directory literally
     * called "2024" therefore still resolves, by passing the count first
     * (`files ls DCIM 20` vs `files ls 2024` reading the directory).
     *
     * No path is resolved here: [FileGuard.resolve] is the single place that
     * decides what is reachable, and it runs in the executor so an invalid path
     * is a runtime error with its own sentence rather than a parse failure.
     */
    private fun parseFiles(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return FILES_USAGE
        val verb = tokens[0].text.lowercase()
        val body = dropLeadingWhitespace(rest.substring(tokens[0].start + tokens[0].text.length))
        return when (verb) {
            "ls" -> {
                val inner = tokenSpans(body)
                var limit = FileGuard.LIMIT_DEFAULT
                var path = body.trim()
                if (inner.size >= 2) {
                    val last = inner[inner.size - 1]
                    val parsed = last.text.toIntOrNull()
                    if (parsed != null) {
                        limit = FileGuard.clampLimit(parsed)
                        path = body.substring(0, last.start).trim()
                    }
                }
                Command.FilesList(if (path.isEmpty()) null else path, limit)
            }

            "cat", "info", "mkdir" -> {
                if (body.isBlank()) return FILES_USAGE
                when (verb) {
                    "cat" -> Command.FilesCat(body)
                    "info" -> Command.FilesInfo(body)
                    else -> Command.FilesMkdir(body)
                }
            }

            "rm" -> {
                var target = body
                var apply = false
                val cut = firstWhitespace(target)
                val head = if (cut < 0) target else target.substring(0, cut)
                if (head.equals("apply", ignoreCase = true)) {
                    apply = true
                    target = if (cut < 0) "" else dropLeadingWhitespace(target.substring(cut))
                }
                if (target.isBlank()) return FILES_USAGE
                Command.FilesRemove(target, apply)
            }

            // The count comes off the *end*, the keyword is the token before it,
            // and everything left is the path — the only split that survives a
            // directory with a space in its name ("My Documents").
            "find" -> {
                val inner = tokenSpans(body)
                var limit = FindPlan.LIMIT_DEFAULT
                var end = inner.size
                if (end >= 3) {
                    val parsed = inner[end - 1].text.toIntOrNull()
                    if (parsed != null) {
                        limit = FindPlan.clampLimit(parsed)
                        end -= 1
                    }
                }
                if (end < 2) return FILES_USAGE
                val keyword = inner[end - 1].text
                val path = body.substring(0, inner[end - 1].start).trim()
                if (path.isEmpty() || keyword.isEmpty()) return FILES_USAGE
                Command.FilesFind(path, keyword, limit)
            }

            "df" -> {
                if (body.isNotBlank()) return FILES_USAGE
                Command.FilesDf
            }

            "pull" -> {
                if (body.isBlank()) return FILES_USAGE
                Command.FilesPull(body)
            }

            else -> FILES_USAGE
        }
    }

    /**
     * `notif [list] [n] [q=TEXT]` (SPEC.md section 3.8).
     *
     * The query keeps its inner spacing verbatim, so `notif q=微信 消息` searches
     * for the two-word phrase instead of two separate words.
     */
    private fun parseNotif(rest: String): Command {
        val tokens = tokenSpans(rest)
        var index = 0
        if (index < tokens.size && tokens[index].text.equals("list", ignoreCase = true)) {
            index++
        }
        var limit = NotifPlan.LIMIT_DEFAULT
        if (index < tokens.size) {
            val parsed = tokens[index].text.toIntOrNull()
            if (parsed != null) {
                limit = NotifPlan.clampLimit(parsed)
                index++
            }
        }
        if (index >= tokens.size) return Command.NotifList(limit, null)
        val head = tokens[index].text
        if (head.length <= 2 || !head.startsWith("q=", ignoreCase = true)) return NOTIF_USAGE
        val query = rest.substring(tokens[index].start + 2).trim()
        if (query.isEmpty()) return NOTIF_USAGE
        return Command.NotifList(limit, query)
    }

    /**
     * `say [-l zh|en] [rate=PERCENT] [pitch=PERCENT] TEXT` (SPEC.md §3.9).
     *
     * Only *leading* flags are consumed, so a sentence that happens to start
     * with the word keeps its text; the payload is the rest of the line
     * verbatim, spaces included. `-l` also accepts `-l=en`, because both
     * spellings come naturally to a person typing into a chat box.
     */
    private fun parseSay(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return SAY_USAGE
        var index = 0
        var rate = SayPlan.RATE_DEFAULT
        var pitch = SayPlan.PITCH_DEFAULT
        var lang = SayPlan.LANG_DEFAULT
        while (index < tokens.size) {
            val head = tokens[index].text
            val lower = head.lowercase()
            if (lower == "-l" || lower == "--lang") {
                if (index + 1 >= tokens.size) return SAY_USAGE
                lang = SayPlan.lang(tokens[index + 1].text) ?: return SAY_USAGE
                index += 2
                continue
            }
            if (lower.startsWith("-l=") && head.length > 3) {
                lang = SayPlan.lang(head.substring(3)) ?: return SAY_USAGE
                index++
                continue
            }
            if (lower.startsWith("rate=") && head.length > 5) {
                val parsed = head.substring(5).toIntOrNull() ?: return SAY_USAGE
                rate = SayPlan.clampRate(parsed)
                index++
                continue
            }
            if (lower.startsWith("pitch=") && head.length > 6) {
                val parsed = head.substring(6).toIntOrNull() ?: return SAY_USAGE
                pitch = SayPlan.clampPitch(parsed)
                index++
                continue
            }
            break
        }
        if (index >= tokens.size) return SAY_USAGE
        val text = rest.substring(tokens[index].start)
        if (text.isBlank()) return SAY_USAGE
        return Command.Say(text, rate, pitch, lang)
    }

    /**
     * `screen stayon [on|off] | screen timeout MS | screen on | screen off` (§3.14).
     *
     * `stayon` takes an *optional* argument because the report form is the one an
     * operator types when they cannot remember what they set.
     */
    private fun parseScreen(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return SCREEN_USAGE
        when (tokens[0].text.lowercase()) {
            "on" -> return if (tokens.size > 1) SCREEN_USAGE else Command.ScreenOn
            "off" -> return if (tokens.size > 1) SCREEN_USAGE else Command.ScreenOff
            "stayon", "stay-on" -> {
                if (tokens.size == 1) return Command.ScreenStayOn(null)
                if (tokens.size > 2) return SCREEN_USAGE
                return when (tokens[1].text.lowercase()) {
                    "on", "1", "true" -> Command.ScreenStayOn(true)
                    "off", "0", "false" -> Command.ScreenStayOn(false)
                    else -> SCREEN_USAGE
                }
            }

            "timeout" -> {
                if (tokens.size != 2) return SCREEN_USAGE
                val parsed = tokens[1].text.toLongOrNull() ?: return SCREEN_USAGE
                return Command.ScreenTimeout(ScreenPlan.clampTimeout(parsed))
            }

            else -> return SCREEN_USAGE
        }
    }

    /**
     * `sms [list] [n] | sms send [apply] NUMBER TEXT` (SPEC.md section 3.12).
     *
     * The number is normalised at parse time so a typo never reaches the
     * telephony stack; the body keeps its inner spacing verbatim.
     */
    private fun parseSms(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return SMS_USAGE
        return when (tokens[0].text.lowercase()) {
            "list" -> {
                if (tokens.size > 2) return SMS_USAGE
                if (tokens.size == 1) return Command.SmsList(SmsPlan.LIMIT_DEFAULT)
                val parsed = tokens[1].text.toIntOrNull() ?: return SMS_USAGE
                Command.SmsList(SmsPlan.clampLimit(parsed))
            }

            "send" -> {
                var index = 1
                var apply = false
                if (index < tokens.size && tokens[index].text.equals("apply", ignoreCase = true)) {
                    apply = true
                    index++
                }
                if (index >= tokens.size) return SMS_USAGE
                val number = SmsPlan.normalizeNumber(tokens[index].text) ?: return SMS_USAGE
                val afterNumber = tokens[index].start + tokens[index].text.length
                val body = dropLeadingWhitespace(rest.substring(afterNumber)).trim()
                if (body.isEmpty()) return SMS_USAGE
                Command.SmsSend(number, body, apply)
            }

            else -> SMS_USAGE
        }
    }

    /** `anim [get] | anim set on|off|SCALE | anim save | anim restore` (§3.11). */
    private fun parseAnim(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return Command.AnimGet
        return when (tokens[0].text.lowercase()) {
            "get" -> {
                if (tokens.size > 1) ANIM_USAGE else Command.AnimGet
            }

            "set" -> {
                if (tokens.size != 2) return ANIM_USAGE
                val value = AnimPlan.scale(tokens[1].text) ?: return ANIM_USAGE
                Command.AnimSet(value)
            }

            "save" -> {
                if (tokens.size > 1) ANIM_USAGE else Command.AnimSave
            }

            "restore" -> {
                if (tokens.size > 1) ANIM_USAGE else Command.AnimRestore
            }

            else -> ANIM_USAGE
        }
    }

    /**
     * `call [apply] NUMBER | call answer | call hangup | call state` (§3.13).
     *
     * `answer`, `hangup` and `state` are reserved words, so a number can never
     * be mistaken for one of them; anything else must normalise to a number.
     */
    private fun parseCall(rest: String): Command {
        val tokens = tokenSpans(rest)
        if (tokens.isEmpty()) return CALL_USAGE
        when (tokens[0].text.lowercase()) {
            "answer" -> return if (tokens.size > 1) CALL_USAGE else Command.CallAnswer
            "hangup" -> return if (tokens.size > 1) CALL_USAGE else Command.CallHangup
            "state" -> return if (tokens.size > 1) CALL_USAGE else Command.CallState
            "speaker" -> {
                if (tokens.size == 1) return Command.CallSpeaker(null)
                if (tokens.size > 2) return CALL_USAGE
                return when (tokens[1].text.lowercase()) {
                    "on", "1", "true" -> Command.CallSpeaker(true)
                    "off", "0", "false" -> Command.CallSpeaker(false)
                    else -> CALL_USAGE
                }
            }
        }
        var index = 0
        var apply = false
        if (tokens[0].text.equals("apply", ignoreCase = true)) {
            apply = true
            index = 1
        }
        if (index >= tokens.size) return CALL_USAGE
        if (tokens.size - index != 1) return CALL_USAGE
        val number = CallPlan.normalize(tokens[index].text) ?: return CALL_USAGE
        return Command.CallDial(number, apply)
    }

    /** A dotted, non-empty application id: what `uninstall` accepts. */
    private fun isPackageName(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.contains(WHITESPACE)) return false
        if (!value.contains('.')) return false
        return value.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    /** A whitespace-separated token plus where it starts, for verbatim slicing. */
    private class TokenSpan(val text: String, val start: Int)

    private fun tokenSpans(rest: String): List<TokenSpan> {
        val spans = ArrayList<TokenSpan>()
        var i = 0
        while (i < rest.length) {
            while (i < rest.length && rest[i].isWhitespace()) i++
            if (i >= rest.length) break
            val start = i
            while (i < rest.length && !rest[i].isWhitespace()) i++
            spans.add(TokenSpan(rest.substring(start, i), start))
        }
        return spans
    }

    private fun firstWhitespace(value: String): Int {
        var i = 0
        while (i < value.length) {
            if (value[i].isWhitespace()) return i
            i++
        }
        return -1
    }

    private fun splitArgs(rest: String): List<String> {
        val trimmed = rest.trim()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(WHITESPACE)
    }

    /** Missing argument -> default. Present but not an integer -> null. */
    private fun optionalInt(args: List<String>, index: Int, default: Int): Int? {
        if (index >= args.size) return default
        return args[index].toIntOrNull()
    }

    private fun dropLeadingWhitespace(value: String): String {
        var i = 0
        while (i < value.length && value[i].isWhitespace()) i++
        return value.substring(i)
    }
}
