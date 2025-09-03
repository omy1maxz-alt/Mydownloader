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
        // This tells Gradle where to find the GeckoView library
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "MyDownloader"
include(":app")