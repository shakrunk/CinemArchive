pluginManagement {
    repositories {
        google()
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

rootProject.name = "CinemArchiveAndroid"

include(":app")
include(":core:designsystem")
include(":core:model")
include(":core:database")
include(":data")
include(":feature:library")
include(":feature:ledger")
include(":feature:settings")
include(":feature:auth")
include(":feature:discover")
include(":feature:upnext")
