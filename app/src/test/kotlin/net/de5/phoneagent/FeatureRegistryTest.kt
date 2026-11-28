package net.de5.phoneagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module foundation (v0.8.0-pad12, SPEC.md §3.28).
 *
 * The whole point of the registry is that feature number 13 costs one new file
 * plus one line here, so these tests pin exactly that contract: no duplicate
 * verbs, no verb that collides with the existing 82 commands, and a parse path
 * that falls through to `unknown command` when nobody claims the word.
 */
class FeatureRegistryTest {

    @Test
    fun everyModuleClaimsExactlyOneUniqueLowercaseVerb() {
        val verbs = FeatureRegistry.verbs()
        assertEquals(7, verbs.size)
        assertEquals(verbs.size, verbs.toSet().size)
        for (verb in verbs) {
            assertEquals(verb.lowercase(), verb)
            assertTrue(verb.isNotBlank())
        }
        assertEquals(emptyList<String>(), FeatureRegistry.duplicateVerbs())
    }

    @Test
    fun theFirstBatchIsRegistered() {
        assertEquals(
            listOf("where", "media", "battery", "notify", "wm", "autowake", "speak"),
            FeatureRegistry.verbs()
        )
    }

    @Test
    fun anUnclaimedWordFallsThroughToUnknown() {
        assertNull(FeatureRegistry.parse("definitelynotacommand", ""))
        assertTrue(Commands.parse("definitelynotacommand") is Command.Unknown)
    }

    @Test
    fun aClaimedWordBecomesAModuleCommandCarryingTheNormalisedArguments() {
        assertEquals(Command.Module("media", "next"), Commands.parse("media next"))
        assertEquals(Command.Module("media", "next"), Commands.parse("media NEXT"))
        assertEquals(Command.Module("where", ""), Commands.parse("where"))
        assertEquals(Command.Module("battery", ""), Commands.parse("battery"))
    }

    @Test
    fun badArgumentsComeBackAsBadUsageWithAUsageLine() {
        val media = Commands.parse("media")
        assertTrue(media is Command.BadUsage)
        assertTrue((media as Command.BadUsage).detail.startsWith("usage: "))

        val battery = Commands.parse("battery maybe")
        assertTrue(battery is Command.BadUsage)

        val autowake = Commands.parse("autowake sometimes")
        assertTrue(autowake is Command.BadUsage)

        val wm = Commands.parse("wm size nonsense apply")
        assertTrue(wm is Command.BadUsage)

        val notify = Commands.parse("notify post onlytitle")
        assertTrue(notify is Command.BadUsage)
    }

    @Test
    fun executingAModuleThatIsNotRegisteredReportsUnknownInsteadOfCrashing() {
        val result = FeatureRegistry.execute(Command.Module("nosuchmodule", ""), FakeFeatureContext())
        assertEquals(EXIT_USAGE, result.exit)
        assertTrue(result.stderr.startsWith("unknown command: "))
    }

    @Test
    fun theExistingCommandsAreUntouched() {
        // The 82 pre-existing verbs still parse to their own classes; the module
        // hook only runs on the else branch.
        assertEquals(Command.Ping, Commands.parse("ping"))
        assertEquals(Command.Wake, Commands.parse("wake"))
        assertEquals(Command.Status, Commands.parse("status"))
        assertEquals(Command.Stop, Commands.parse("stop"))
    }

    @Test
    fun aFakeContextIsEnoughToDriveAModuleWithoutAPhone() {
        val ctx = FakeFeatureContext()
        assertEquals(true, ctx.applyAllowed)
        assertNull(ctx.runShell("true", 1_000L))
    }
}

/**
 * A [FeatureContext] with no phone behind it.
 *
 * Only the shell-free halves of the modules are exercised here; anything that
 * needs a live device is covered by the plan objects instead.
 */
internal class FakeFeatureContext(
    private val shell: ShellOut? = null
) : FeatureContext {

    // Nothing here touches the Context: the shell-free halves of the modules are
    // driven through the plan objects instead. Touching it means the test wants a
    // phone, and it should fail loudly rather than quietly get a stub.
    override val context: android.content.Context
        get() = throw UnsupportedOperationException("no Context in a JVM unit test")

    override val applyAllowed: Boolean = true

    override fun <T> onMain(timeoutMs: Long, block: () -> T): T? = block()

    override fun runShell(command: String, timeoutMs: Long): ShellOut? = shell
}
