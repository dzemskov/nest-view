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

rootProject.name = "nest-view"

include(
    ":app",
    ":core:model",
    ":core:analysis",
    ":core:sdm",
    ":core:streaming",
    ":core:data",
    ":core:designsystem",
    ":feature:monitor",
    ":feature:setup",
    ":feature:settings",
)
