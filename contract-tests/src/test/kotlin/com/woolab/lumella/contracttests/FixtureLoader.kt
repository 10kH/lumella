package com.woolab.lumella.contracttests

import java.io.File

/**
 * Robust `fixtures/` JSON fixture resolver, tolerant of the working
 * directory Gradle happens to launch the test JVM from (module dir vs.
 * root project dir) — same pattern as `DependencyRuleGuardTest`'s
 * `resolveAppBuildFile`.
 */
object FixtureLoader {
    fun resolve(name: String): File {
        val candidates = listOf(
            File("fixtures/$name"),
            File("contract-tests/fixtures/$name"),
            File("../contract-tests/fixtures/$name"),
            File("../fixtures/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "could not locate fixtures/$name from working directory " + File(".").absoluteFile.normalize()
        }
        return found
    }

    fun readText(name: String): String = resolve(name).readText(Charsets.UTF_8)
}
