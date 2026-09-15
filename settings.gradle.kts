pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AxBrowser"

include(":app")
include(":core:core-ui")
include(":core:core-domain")
include(":core:core-data")
include(":core:core-testing")
include(":feature:feature-browser")
include(":feature:feature-downloads")
include(":feature:feature-bookmarks")
include(":feature:feature-history")
include(":feature:feature-settings")
include(":feature:feature-filemanager")
include(":feature:feature-videoplayer")
include(":feature:feature-pentest")
