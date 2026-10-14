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
    data class Shot(val maxPx: Int, val quality: Int) : Command()
    data class Tree(val maxNodes: Int) : Command()
    data class Tap(val x: Int, val y: Int) : Command()
    data class LongPress(val x: Int, val y: Int, val ms: Int) : Command()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Int) : Command()
    data class Key(val name: String) : Command()
    /** `text` keeps the rest of the line verbatim, spaces included. */
    data class Text(val value: String) : Command()
    object Apps : Command()
    data class App(val pkg: String) : Command()
    object Loc : Command()
    data class LocLoop(val interval: Int) : Command()
    object LocStop : Command()
    object Stop : Command()
    /** The verb is not part of the grammar. */
    data class Unknown(val name: String) : Command()
    /** The verb is known but the arguments cannot be used. */
    data class BadUsage(val detail: String) : Command()
}

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

    private val KEY_NAMES = setOf("back", "home", "recents", "notifications", "quicksettings")

    /**
     * Split one command line. Never throws: every failure path returns
     * [Command.Unknown] or [Command.BadUsage], both of which the executor
     * reports as `exit=2`.
     */
    fun parse(line: String): Command {
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
                val maxPx = optionalInt(args, 0, SHOT_MAXPX_DEFAULT)
                    ?: return Command.BadUsage("usage: shot [maxPx] [quality]")
                val quality = optionalInt(args, 1, SHOT_QUALITY_DEFAULT)
                    ?: return Command.BadUsage("usage: shot [maxPx] [quality]")
                Command.Shot(
                    maxPx.coerceIn(SHOT_MAXPX_MIN, SHOT_MAXPX_MAX),
                    quality.coerceIn(SHOT_QUALITY_MIN, SHOT_QUALITY_MAX)
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

            "stop" -> Command.Stop

            else -> Command.Unknown(head)
        }
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
