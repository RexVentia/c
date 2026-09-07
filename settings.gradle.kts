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

rootProject.name = "RobotSimulator"
include(":app")
include(":simulation-core")
include(":bridge-contract")
include(":hook-bridge")
include(":test-target")
include(":xposed-api-stubs")
