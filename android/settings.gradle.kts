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

rootProject.name = "Paperboat"
include(":app")

// The PAL game (libmain_pal.so). -Ppaperboat.pal=false leaves it out for a
// faster US-only build; the launcher then reports PAL ROMs as unsupported.
if (providers.gradleProperty("paperboat.pal").orNull != "false") {
    include(":palcore")
}
