package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The gesture rules are duplicated across two apps with no shared module, and they drifted
 * once — this app held a revision of them from before the other fixed the ordering, which put
 * the exit confirmation back within reach of two contacts.
 *
 * So the rule file is pinned against a checked-in copy that both repos carry identically.
 * Editing the rule fails this test until the copy is updated, and updating the copy shows up
 * in a diff that a reviewer can compare against the sibling's. It works in a lone clone, which
 * a cross-repo file comparison did not.
 */
class RightTapRulesDriftTest {

    @Test
    fun `the rule file matches the copy shared with the sibling app`() {
        val expected = javaClass.classLoader
            ?.getResourceAsStream("RightTapDecision.shared.txt")
            ?.bufferedReader()?.readText()
        assertNotNull("RightTapDecision.shared.txt is missing from test resources", expected)

        val actual = run {
            // Gradle runs unit tests from the module directory, so this is module-relative and
            // needs no knowledge of where the repo sits. It asserts the file exists rather
            // than skipping: an earlier version of this guard quietly passed for days because
            // its path was wrong.
            val f = java.io.File("src/main/java/com/woolab/lumella/RightTapDecision.kt")
            org.junit.Assert.assertTrue("cannot find ${f.absolutePath}", f.isFile)
            f.readText().replace("package com.woolab.lumella", "package PKG")
        }

        assertEquals(
            "RightTapDecision.kt no longer matches the shared copy. If this change is " +
                "intended, apply the SAME change to the sibling app and update " +
                "app/src/test/resources/RightTapDecision.shared.txt in BOTH repos — these two " +
                "have already diverged once, on the gesture that closes the app.",
            expected,
            actual,
        )
    }
}
