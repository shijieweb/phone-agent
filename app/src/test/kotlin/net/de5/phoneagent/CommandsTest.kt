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
}
