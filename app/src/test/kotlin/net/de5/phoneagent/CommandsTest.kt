package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SPEC.md section 10 case 1: every command, its defaults and its clamps. */
class CommandsTest {

    @Test
    fun pingAndStatus() {
        assertEquals(Command.Ping, Commands.parse("ping"))
        assertEquals(Command.Status, Commands.parse("status"))
    }

    @Test
    fun shotUsesDefaults() {
        assertEquals(Command.Shot(720, 55), Commands.parse("shot"))
    }

    @Test
    fun shotKeepsExplicitArguments() {
        assertEquals(Command.Shot(1080, 80), Commands.parse("shot 1080 80"))
    }

    @Test
    fun shotClampsMaxPxAndQuality() {
        assertEquals(Command.Shot(320, 20), Commands.parse("shot 100 1"))
        assertEquals(Command.Shot(1440, 90), Commands.parse("shot 4000 100"))
    }

    @Test
    fun shotRejectsNonNumericArguments() {
        assertTrue(Commands.parse("shot abc 55") is Command.BadUsage)
        assertTrue(Commands.parse("shot 720 abc") is Command.BadUsage)
    }

    @Test
    fun treeDefaultsAndCap() {
        assertEquals(Command.Tree(400), Commands.parse("tree"))
        assertEquals(Command.Tree(800), Commands.parse("tree 5000"))
        assertEquals(Command.Tree(1), Commands.parse("tree 0"))
    }

    @Test
    fun tapNeedsTwoIntegers() {
        assertEquals(Command.Tap(10, 20), Commands.parse("tap 10 20"))
        assertTrue(Commands.parse("tap 10") is Command.BadUsage)
        assertTrue(Commands.parse("tap a b") is Command.BadUsage)
    }

    @Test
    fun longPressDefaultsToSixHundredMilliseconds() {
        assertEquals(Command.LongPress(1, 2, 600), Commands.parse("long 1 2"))
        assertEquals(Command.LongPress(1, 2, 1200), Commands.parse("long 1 2 1200"))
    }

    @Test
    fun longPressCapsAtFiveSeconds() {
        assertEquals(Command.LongPress(1, 2, 5000), Commands.parse("long 1 2 9000"))
        assertEquals(Command.LongPress(1, 2, 1), Commands.parse("long 1 2 0"))
    }

    @Test
    fun swipeDefaultsToThreeHundredMilliseconds() {
        assertEquals(Command.Swipe(1, 2, 3, 4, 300), Commands.parse("swipe 1 2 3 4"))
        assertEquals(Command.Swipe(1, 2, 3, 4, 3000), Commands.parse("swipe 1 2 3 4 9999"))
        assertTrue(Commands.parse("swipe 1 2 3") is Command.BadUsage)
    }

    @Test
    fun keyAcceptsTheFiveGlobalActions() {
        assertEquals(Command.Key("back"), Commands.parse("key back"))
        assertEquals(Command.Key("home"), Commands.parse("key home"))
        assertEquals(Command.Key("recents"), Commands.parse("key recents"))
        assertEquals(Command.Key("notifications"), Commands.parse("key notifications"))
        assertEquals(Command.Key("quicksettings"), Commands.parse("key quicksettings"))
        assertEquals(Command.Key("home"), Commands.parse("key HOME"))
    }

    @Test
    fun keyRejectsAnythingElse() {
        assertTrue(Commands.parse("key nope") is Command.BadUsage)
        assertTrue(Commands.parse("key") is Command.BadUsage)
    }

    @Test
    fun textKeepsTheRestOfTheLineVerbatim() {
        assertEquals(Command.Text("hello"), Commands.parse("text hello"))
        assertEquals(Command.Text("hello  world"), Commands.parse("text hello  world"))
        assertEquals(Command.Text("a b\tc"), Commands.parse("text a b\tc"))
        assertEquals(Command.Text("hello  world"), Commands.parse("text   hello  world"))
        assertTrue(Commands.parse("text") is Command.BadUsage)
    }

    @Test
    fun appsAndAppLaunch() {
        assertEquals(Command.Apps, Commands.parse("apps"))
        assertEquals(Command.App("net.de5.phoneagent"), Commands.parse("app net.de5.phoneagent"))
        assertTrue(Commands.parse("app") is Command.BadUsage)
    }

    @Test
    fun locLocLoopAndLocStop() {
        assertEquals(Command.Loc, Commands.parse("loc"))
        assertEquals(Command.LocStop, Commands.parse("locstop"))
    }

    @Test
    fun locLoopIsClampedToThirtySecondsMinimum() {
        assertEquals(Command.LocLoop(300), Commands.parse("locloop 300"))
        assertEquals(Command.LocLoop(30), Commands.parse("locloop 5"))
        assertEquals(Command.LocLoop(3600), Commands.parse("locloop 99999"))
        assertTrue(Commands.parse("locloop") is Command.BadUsage)
        assertTrue(Commands.parse("locloop soon") is Command.BadUsage)
    }

    @Test
    fun stopStopsTheAgent() {
        assertEquals(Command.Stop, Commands.parse("stop"))
    }

    @Test
    fun unknownCommandKeepsItsName() {
        assertEquals(Command.Unknown("frobnicate"), Commands.parse("frobnicate"))
        assertEquals(Command.Unknown(""), Commands.parse("   "))
        assertEquals(Command.Unknown(""), Commands.parse(""))
    }

    @Test
    fun commandVerbIsCaseInsensitive() {
        assertEquals(Command.Ping, Commands.parse("PING"))
        assertEquals(Command.LocStop, Commands.parse("LocStop"))
    }

    @Test
    fun extraArgumentsAreIgnored() {
        assertEquals(Command.Ping, Commands.parse("ping extra"))
        assertEquals(Command.Apps, Commands.parse("apps 1 2 3"))
    }

    // ------------------------------------------- element-level actions (3.1)

    @Test
    fun clickKeepsTheWholeRestOfTheLineAsItsSelector() {
        assertEquals(Command.Click(Selector.parse("登录")), Commands.parse("click 登录"))
        assertEquals(
            Command.Click(Selector.parse("text:delete all")),
            Commands.parse("click text:delete all")
        )
        // Inner spaces survive; only the separator is dropped.
        assertEquals(
            Command.Click(Selector.parse("a  b")),
            Commands.parse("click   a  b")
        )
    }

    @Test
    fun clickNeedsASelector() {
        assertTrue(Commands.parse("click") is Command.BadUsage)
        assertTrue(Commands.parse("click   ") is Command.BadUsage)
    }

    @Test
    fun longClickMirrorsClick() {
        assertEquals(Command.LongClick(Selector.parse("确定")), Commands.parse("longclick 确定"))
        assertTrue(Commands.parse("longclick") is Command.BadUsage)
    }

    @Test
    fun scrollKeepsItsDirectionCountAndOptionalSelector() {
        assertEquals(Command.Scroll(Direction.DOWN, 1, null), Commands.parse("scroll down"))
        assertEquals(Command.Scroll(Direction.UP, 1, null), Commands.parse("scroll up"))
        assertEquals(Command.Scroll(Direction.DOWN, 3, null), Commands.parse("scroll down 3"))
        assertEquals(
            Command.Scroll(Direction.DOWN, 3, Selector.parse("list item")),
            Commands.parse("scroll down 3 list item")
        )
        assertEquals(
            Command.Scroll(Direction.UP, 2, Selector.parse("id:feed")),
            Commands.parse("scroll up 2 id:feed")
        )
    }

    @Test
    fun scrollKeepsTheSelectorSpacingVerbatim() {
        // Inner runs of spaces belong to the selector, exactly like `text`.
        assertEquals(
            Command.Scroll(Direction.DOWN, 1, Selector.parse("a  b")),
            Commands.parse("scroll down   a  b")
        )
    }

    @Test
    fun scrollDefaultsToASingleStepAndCapsAtTwenty() {
        assertEquals(Command.Scroll(Direction.DOWN, 20, null), Commands.parse("scroll down 99"))
        assertEquals(Command.Scroll(Direction.UP, 1, null), Commands.parse("scroll up 0"))
    }

    @Test
    fun scrollWithoutAValidDirectionIsBadUsage() {
        assertTrue(Commands.parse("scroll") is Command.BadUsage)
        assertTrue(Commands.parse("scroll sideways") is Command.BadUsage)
    }

    /**
     * `scroll down <selector>` and `scroll down <n> <selector>` differ only by
     * whether the second token parses as a number, so both must be unambiguous.
     */
    @Test
    fun aNumericSelectorIsNotMistakenForACount() {
        // `123` parses as a count, leaving no selector.
        assertEquals(Command.Scroll(Direction.DOWN, 5, null), Commands.parse("scroll down 5"))
        // A value that is documented as a selector keeps its full text.
        val withNumberLikeText = Commands.parse("scroll down text:123")
        assertEquals(
            Command.Scroll(Direction.DOWN, 1, Selector.parse("text:123")),
            withNumberLikeText
        )
    }

    @Test
    fun imeEnterNeedsTheWordEnter() {
        assertEquals(Command.ImeEnter, Commands.parse("ime enter"))
        assertEquals(Command.ImeEnter, Commands.parse("ime ENTER"))
        assertTrue(Commands.parse("ime") is Command.BadUsage)
        assertTrue(Commands.parse("ime search") is Command.BadUsage)
    }

    @Test
    fun wakeIsACommand() {
        assertEquals(Command.Wake, Commands.parse("wake"))
    }

    // -------------------------------------------------- privileged commands

    @Test
    fun installKeepsTheWholeUrlVerbatim() {
        // Query strings contain characters the other parsers split on, so the
        // URL must survive exactly as typed.
        val url = "https://example.com/a.apk?x=1&y=2#frag"
        assertEquals(Command.Install(url), Commands.parse("install " + url))
        assertEquals(
            Command.Install("http://192.168.5.200:8080/phone-agent.apk"),
            Commands.parse("install   http://192.168.5.200:8080/phone-agent.apk  ")
        )
        assertEquals(
            Command.Install("https://example.com/x.apk?q=a b"),
            Commands.parse("install https://example.com/x.apk?q=a b")
        )
    }

    @Test
    fun installRejectsNonUrl() {
        assertTrue(Commands.parse("install") is Command.BadUsage)
        assertTrue(Commands.parse("install   ") is Command.BadUsage)
        assertTrue(Commands.parse("install /sdcard/a.apk") is Command.BadUsage)
        assertTrue(Commands.parse("install ftp://example.com/a.apk") is Command.BadUsage)
        // Scheme matching is case-insensitive, like the browser.
        assertEquals(
            Command.Install("HTTPS://example.com/a.apk"),
            Commands.parse("install HTTPS://example.com/a.apk")
        )
    }

    @Test
    fun uninstallWantsAPackageName() {
        assertEquals(
            Command.Uninstall("com.example.app"),
            Commands.parse("uninstall com.example.app")
        )
        assertEquals(
            Command.Uninstall("com.example.my_app2"),
            Commands.parse("uninstall   com.example.my_app2   ")
        )
        assertTrue(Commands.parse("uninstall") is Command.BadUsage)
        assertTrue(Commands.parse("uninstall nosuchapp") is Command.BadUsage)
        assertTrue(Commands.parse("uninstall com.example.app extra") is Command.BadUsage)
        assertTrue(Commands.parse("uninstall com.exa mple.app") is Command.BadUsage)
    }

    @Test
    fun settingsReadsAndWrites() {
        assertEquals(Command.Settings("secure", "a11y_enabled", null), Commands.parse("settings secure a11y_enabled"))
        assertEquals(
            Command.Settings("global", "device_provisioned", "1"),
            Commands.parse("settings global device_provisioned 1")
        )
        assertEquals(
            Command.Settings("system", "screen_brightness", "120"),
            Commands.parse("settings SYSTEM screen_brightness 120")
        )
        // The value keeps its inner spacing: re-joining tokens would flatten it.
        assertEquals(
            Command.Settings("secure", "some_key", "a  b  c"),
            Commands.parse("settings secure some_key a  b  c")
        )
    }

    @Test
    fun settingsRejectsBadNamespaceOrMissingKey() {
        assertTrue(Commands.parse("settings") is Command.BadUsage)
        assertTrue(Commands.parse("settings secure") is Command.BadUsage)
        assertTrue(Commands.parse("settings unknown key") is Command.BadUsage)
    }

    @Test
    fun logsDefaultsAndClamps() {
        assertEquals(Command.Logs(120), Commands.parse("logs"))
        assertEquals(Command.Logs(50), Commands.parse("logs 50"))
        // Above the cap, and nonsense below it, both land on the clamped value.
        assertEquals(Command.Logs(500), Commands.parse("logs 100000"))
        assertEquals(Command.Logs(1), Commands.parse("logs 0"))
        assertTrue(Commands.parse("logs many") is Command.BadUsage)
    }

    @Test
    fun adminHasTwoActions() {
        assertEquals(Command.AdminStatus, Commands.parse("admin status"))
        assertEquals(Command.AdminRelease, Commands.parse("admin RELEASE"))
        assertTrue(Commands.parse("admin") is Command.BadUsage)
        assertTrue(Commands.parse("admin reboot") is Command.BadUsage)
    }

    @Test
    fun a11yOnlyHeals() {
        assertEquals(Command.A11yHeal, Commands.parse("a11y heal"))
        assertEquals(Command.A11yHeal, Commands.parse("a11y HEAL"))
        assertTrue(Commands.parse("a11y") is Command.BadUsage)
        assertTrue(Commands.parse("a11y on") is Command.BadUsage)
    }

    @Test
    fun lockAndRebootAreCommands() {
        assertEquals(Command.Lock, Commands.parse("lock"))
        assertEquals(Command.Reboot, Commands.parse("reboot"))
    }

    /**
     * The explicit `get`/`put` verbs mirror adb's `settings`, which is what the
     * operator already has in muscle memory; the bare form stays valid so
     * existing scripts keep working.
     */
    @Test
    fun settingsAcceptsExplicitGetAndPutVerbs() {
        assertEquals(
            Command.Settings("secure", "enabled_accessibility_services", null),
            Commands.parse("settings get secure enabled_accessibility_services")
        )
        // `get` forces a read even when a third token looks like a value.
        assertEquals(
            Command.Settings("global", "adb_enabled", null),
            Commands.parse("settings get global adb_enabled")
        )
        assertEquals(
            Command.Settings("global", "adb_enabled", "1"),
            Commands.parse("settings put global adb_enabled 1")
        )
        assertEquals(
            Command.Settings("system", "screen_off_timeout", "600000"),
            Commands.parse("settings PUT system screen_off_timeout 600000")
        )
        // A value with inner spacing survives the verb form too.
        assertEquals(
            Command.Settings("secure", "k", "a  b"),
            Commands.parse("settings put secure k a  b")
        )
        // Verb alone, or verb plus half an address, is still a usage error.
        assertTrue(Commands.parse("settings get") is Command.BadUsage)
        assertTrue(Commands.parse("settings get secure") is Command.BadUsage)
        assertTrue(Commands.parse("settings put") is Command.BadUsage)
    }

    @Test
    fun confirmTakesALabelAndAnOptionalTimeout() {
        assertEquals(Command.Confirm("安装", 15), Commands.parse("confirm 安装"))
        assertEquals(Command.Confirm("Install", 15), Commands.parse("confirm   Install  "))
        assertEquals(Command.Confirm("安装", 30), Commands.parse("confirm t=30 安装"))
        // The label keeps its inner spacing: it is matched by substring.
        assertEquals(
            Command.Confirm("下一步 继续", 15),
            Commands.parse("confirm 下一步 继续")
        )
        // A label that merely starts with a digit is not a timeout.
        assertEquals(Command.Confirm("2 个选项", 15), Commands.parse("confirm 2 个选项"))
    }

    @Test
    fun confirmClampsItsTimeoutAndRejectsGarbage() {
        // Below the floor and above the ceiling both land on the bounds; the
        // command is a wait, and an unbounded wait is not something a relay
        // command may request.
        assertEquals(Command.Confirm("ok", 1), Commands.parse("confirm t=0 ok"))
        assertEquals(Command.Confirm("ok", 120), Commands.parse("confirm t=9999 ok"))
        assertTrue(Commands.parse("confirm") is Command.BadUsage)
        assertTrue(Commands.parse("confirm   ") is Command.BadUsage)
        assertTrue(Commands.parse("confirm t=abc 安装") is Command.BadUsage)
        assertTrue(Commands.parse("confirm t=30") is Command.BadUsage)
    }

    // ------------------------------------------- wireless debugging (`adb*`)

    @Test
    fun adbInfoTakesNoArguments() {
        assertEquals(Command.AdbInfo, Commands.parse("adbinfo"))
        assertEquals(Command.AdbInfo, Commands.parse("ADBINFO  "))
        // A query that quietly ignored arguments would hide a typo in a script.
        assertTrue(Commands.parse("adbinfo 38181") is Command.BadUsage)
    }

    @Test
    fun adbHealDefaultsToTwentySeconds() {
        assertEquals(Command.AdbHeal(20), Commands.parse("adbheal"))
        assertEquals(Command.AdbHeal(20), Commands.parse("adbheal  "))
        assertEquals(Command.AdbHeal(35), Commands.parse("adbheal t=35"))
        assertEquals(Command.AdbHeal(35), Commands.parse("adbheal T=35"))
    }

    @Test
    fun adbHealClampsItsTimeoutAndRejectsGarbage() {
        // 0 would mean "never wait", 9999 would stall a script's whole budget.
        assertEquals(Command.AdbHeal(1), Commands.parse("adbheal t=0"))
        assertEquals(Command.AdbHeal(60), Commands.parse("adbheal t=9999"))
        assertTrue(Commands.parse("adbheal t=abc") is Command.BadUsage)
        assertTrue(Commands.parse("adbheal 35") is Command.BadUsage)
        assertTrue(Commands.parse("adbheal t=35 now") is Command.BadUsage)
    }

    // ------------------------------------------------------ download (dl)

    @Test
    fun dlTakesAUrlAndGuessesTheName() {
        assertEquals(
            Command.Dl("https://cdn.example.com/a/film.mp4", null, 600),
            Commands.parse("dl https://cdn.example.com/a/film.mp4")
        )
    }

    @Test
    fun dlKeepsTheUrlVerbatim() {
        // A signed link must never be re-joined or split on its query string.
        val url = "https://cdn.example.com/f.mp4?X-Sig=ab/cd&e=1"
        assertEquals(Command.Dl(url, null, 600), Commands.parse("dl " + url))
    }

    @Test
    fun dlTakesAnExplicitNameIncludingSpaces() {
        assertEquals(
            Command.Dl("https://x/y.mp4", "mama movie.mp4", 600),
            Commands.parse("dl https://x/y.mp4 mama movie.mp4")
        )
    }

    @Test
    fun dlTakesALeadingTimeoutAndClampsIt() {
        assertEquals(
            Command.Dl("https://x/y.mp4", null, 300),
            Commands.parse("dl t=300 https://x/y.mp4")
        )
        assertEquals(
            Command.Dl("https://x/y.mp4", null, 3600),
            Commands.parse("dl t=99999 https://x/y.mp4")
        )
        assertEquals(
            Command.Dl("https://x/y.mp4", null, 1),
            Commands.parse("dl t=0 https://x/y.mp4")
        )
    }

    @Test
    fun dlRejectsGarbageWithoutGuesswork() {
        assertTrue(Commands.parse("dl") is Command.BadUsage)
        assertTrue(Commands.parse("dl t=300") is Command.BadUsage)
        assertTrue(Commands.parse("dl t=abc https://x/y.mp4") is Command.BadUsage)
        // A bare path is a typo, not a URL, and must not be silently prefixed.
        assertTrue(Commands.parse("dl /sdcard/x.mp4") is Command.BadUsage)
        assertTrue(Commands.parse("dl ftp://x/y.mp4") is Command.BadUsage)
    }

    // ------------------------------------------------------- shot b64 (v0.3.0)

    @Test
    fun shotKeepsTheBase64PathByDefault() {
        assertEquals(Command.Shot(720, 55, false), Commands.parse("shot"))
        assertEquals(Command.Shot(1080, 80, false), Commands.parse("shot 1080 80"))
    }

    @Test
    fun shotB64ForcesTheBase64Path() {
        assertEquals(Command.Shot(720, 55, true), Commands.parse("shot b64"))
        assertEquals(Command.Shot(1080, 80, true), Commands.parse("shot b64 1080 80"))
        // The flag is a word, not a case-sensitive keyword.
        assertEquals(Command.Shot(1080, 80, true), Commands.parse("shot B64 1080 80"))
        assertEquals(Command.Shot(320, 20, true), Commands.parse("shot b64 100 1"))
    }

    @Test
    fun shotB64StillRejectsGarbage() {
        assertTrue(Commands.parse("shot b64 abc") is Command.BadUsage)
        assertTrue(Commands.parse("shot b64 720 abc") is Command.BadUsage)
    }

    // ------------------------------------------- element verbs: apply (v0.3.0)

    @Test
    fun clickAndLongclickAreDryRunsByDefault() {
        assertEquals(Command.Click(Selector.parse("登录"), false), Commands.parse("click 登录"))
        assertEquals(
            Command.LongClick(Selector.parse("登录"), false),
            Commands.parse("longclick 登录")
        )
    }

    @Test
    fun applyTurnsTheElementVerbIntoARealTap() {
        assertEquals(Command.Click(Selector.parse("登录"), true), Commands.parse("click apply 登录"))
        assertEquals(
            Command.LongClick(Selector.parse("登录"), true),
            Commands.parse("longclick apply 登录")
        )
        // Case is not part of the keyword.
        assertEquals(Command.Click(Selector.parse("登录"), true), Commands.parse("click APPLY 登录"))
    }

    @Test
    fun theSelectorKeepsItsInnerSpacingAfterApply() {
        assertEquals(
            Command.Click(Selector.parse("text:delete all"), true),
            Commands.parse("click apply text:delete all")
        )
        assertEquals(Command.Click(Selector.parse("a  b"), true), Commands.parse("click apply   a  b"))
    }

    @Test
    fun applyWithoutASelectorIsBadUsage() {
        assertTrue(Commands.parse("click apply") is Command.BadUsage)
        assertTrue(Commands.parse("click apply   ") is Command.BadUsage)
        assertTrue(Commands.parse("longclick apply") is Command.BadUsage)
    }

    @Test
    fun aSelectorThatStartsWithApplyStaysReachableThroughAKindPrefix() {
        // "apply" is only consumed as a flag in first position; the kind prefix
        // disambiguates a label that literally starts with the word.
        assertEquals(
            Command.Click(Selector.parse("text:apply button"), false),
            Commands.parse("click text:apply button")
        )
    }

    @Test
    fun confirmIsADryRunByDefaultAndAcceptsApplyAnywhereBeforeTheText() {
        assertEquals(Command.Confirm("安装", 15, false), Commands.parse("confirm 安装"))
        assertEquals(Command.Confirm("安装", 15, true), Commands.parse("confirm apply 安装"))
        assertEquals(Command.Confirm("安装", 30, true), Commands.parse("confirm apply t=30 安装"))
        assertEquals(Command.Confirm("安装", 30, true), Commands.parse("confirm t=30 apply 安装"))
    }

    @Test
    fun confirmStillClampsTheTimeoutAndKeepsDigitLabels() {
        assertEquals(Command.Confirm("ok", 1, false), Commands.parse("confirm t=0 ok"))
        assertEquals(Command.Confirm("ok", 120, false), Commands.parse("confirm t=9999 ok"))
        // A label that starts with a digit is text, not a flag.
        assertEquals(Command.Confirm("2 个选项", 15, false), Commands.parse("confirm 2 个选项"))
    }

    @Test
    fun confirmRejectsGarbage() {
        assertTrue(Commands.parse("confirm") is Command.BadUsage)
        assertTrue(Commands.parse("confirm apply") is Command.BadUsage)
        assertTrue(Commands.parse("confirm t=abc ok") is Command.BadUsage)
    }

    // ------------------------------------------ "#rc:" built-in actions (v0.3.0)

    @Test
    fun anRcLineNeverFallsThroughToTheShellGrammar() {
        assertEquals(Command.RcShot(1280, 72), Commands.parse("#rc: shot {\"width\":1280,\"quality\":72}"))
        assertEquals(Command.RcShot(720, 55), Commands.parse("#rc: shot"))
        assertEquals(Command.RcUnknown("wins"), Commands.parse("#rc: wins"))
        val bad = Commands.parse("#rc: shot {")
        assertTrue(bad is Command.RcBadPayload)
    }
}
