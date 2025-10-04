pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()

        // Add flatDir for local AAR files
        flatDir {
            dirs("app/libs", "app/libs/netstack")
        }
    }
}

rootProject.name = "yankdvpn"
include(":app")
// REMOVE THIS LINE: include(":app:libs:netstack")  ← DELETE THIS!
