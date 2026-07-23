package com.woolab.lumella.contracttests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Automated classpath guard carried from the G002 architect advisory, extended for G006's
 * runtime DI wiring: `:app` MUST declare a dependency on `:tutor-contract` and MUST NOT
 * declare a COMPILE-scope dependency (`implementation`/`api`) on `:luma-adapter` — the
 * concrete engine adapter is wired in at runtime via reflective DI (see
 * `app/brain/BrainFactory.kt`), keeping `debugCompileClasspath` decoupled from the luma
 * engine implementation.
 *
 * G006 adds a legitimate `runtimeOnly(project(":luma-adapter"))` line (puts the adapter
 * class on the installed APK's runtime classpath, per BrainFactory's `Class.forName` lookup,
 * without touching compile classpath) — this guard now asserts the ABSENCE of any
 * `implementation(project(":luma-adapter"))` / `api(project(":luma-adapter"))` compile-scope
 * declaration specifically, rather than a blanket string ban, and additionally asserts the
 * `runtimeOnly` declaration IS present (so the DI seam can't silently regress to "unbound").
 * The `debugCompileClasspath` grep (`./gradlew :app:dependencies --configuration
 * debugCompileClasspath | grep -c luma-adapter` -> 0) is the authoritative Gradle-level check;
 * this test is the fast, always-on JVM-level guard.
 */
class DependencyRuleGuardTest {

    @Test
    fun `app build file depends on tutor-contract and not on luma-adapter at compile scope`() {
        val text = resolveAppBuildFile().readText()

        assertTrue(
            text.contains("project(\":tutor-contract\")"),
            "expected app/build.gradle.kts to declare project(\":tutor-contract\")"
        )
        assertFalse(
            text.contains("implementation(project(\":luma-adapter\"))") ||
                text.contains("api(project(\":luma-adapter\"))"),
            "app/build.gradle.kts must not reference :luma-adapter at compile scope (implementation/api)"
        )
        assertTrue(
            text.contains("runtimeOnly(project(\":luma-adapter\"))"),
            "expected app/build.gradle.kts to bind :luma-adapter at runtime only via runtimeOnly(project(\":luma-adapter\"))"
        )
    }

    /**
     * Resolves `app/build.gradle.kts` relative to this module's project
     * directory, tolerant of the working directory Gradle happens to launch
     * the test JVM from (module dir vs. root project dir).
     */
    private fun resolveAppBuildFile(): File {
        val candidates = listOf(
            File("../app/build.gradle.kts"),
            File("app/build.gradle.kts"),
            File("../../app/build.gradle.kts")
        )
        val found = candidates.firstOrNull { it.isFile }
        checkNotNull(found) {
            "could not locate app/build.gradle.kts from working directory " +
                File(".").absoluteFile.normalize()
        }
        return found
    }
}
