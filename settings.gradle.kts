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
        // NewPipeExtractor (in-app YouTube trailer streams) and its nanojson dependency
        maven("https://jitpack.io")
    }
}
rootProject.name = "StarkTV"
include(":app")
