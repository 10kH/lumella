package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DisplaySettings is duplicated across the two glasses apps with no shared module, exactly
 * like the tap rules — which silently drifted once, on the gesture that closes the app. Same
 * guard, same reasoning: the file is pinned against a checked-in copy both repos carry
 * identically. Editing the class fails this test until the copy is updated, and the copy's
 * diff is what a reviewer holds against the sibling's.
 */
class DisplaySettingsDriftTest {

    @Test
    fun `the retention class matches the copy shared with the sibling app`() {
        val expected = javaClass.classLoader
            ?.getResourceAsStream("DisplaySettings.shared.txt")
            ?.bufferedReader()?.readText()
        assertNotNull("DisplaySettings.shared.txt is missing from test resources", expected)

        val actual = run {
            // Module-relative on purpose: gradle runs unit tests from the module directory,
            // and an earlier guard with wrong path handling passed silently for days.
            val f = java.io.File("src/main/java/com/woolab/lumella/DisplaySettings.kt")
            assertTrue("cannot find ${f.absolutePath}; this check must not skip silently", f.isFile)
            f.readText().replace("package com.woolab.lumella", "package PKG")
        }

        assertEquals(
            "DisplaySettings.kt has drifted from the sibling's copy. If the change is " +
                "intended, apply it to the sibling app and update " +
                "app/src/test/resources/DisplaySettings.shared.txt in BOTH repos.",
            expected,
            actual,
        )
    }
}
