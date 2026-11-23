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
        // A mirror of Maven Central, and the first one that is asked. The main
        // server answers "429 Too Many Requests" when many builds ask for the
        // same artifact at once - the Robolectric jars are popular - and a 429
        // is a hard error that stops the build instead of letting the next
        // repository answer.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2")
        }
        mavenCentral()
    }
}
rootProject.name = "FrameScope"
include(":app")
