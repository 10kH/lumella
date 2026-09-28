pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumella-glasses"
include(":app", ":tutor-contract", ":luma-adapter", ":contract-tests")

// The slow layer lives in its own repository, checked out beside this one. Both tutoring apps
// consume it this way, so a fix lands in both without a port.
includeBuild("../tutor-slowpath")
// The voice taps and take clock (TakeClock, WavTap), shared with ELLA the same way.
includeBuild("../tutor-capture")
