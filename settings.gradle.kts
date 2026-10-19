// Auto-detect Android SDK if local.properties is missing and no env vars set.
val localProps = file("local.properties")
if (!localProps.exists()) {
    val sdkDir = listOfNotNull(
        System.getenv("ANDROID_HOME")?.let { file(it) },
        System.getenv("ANDROID_SDK_ROOT")?.let { file(it) },
        file("${System.getProperty("user.home")}/Android/Sdk"),
        file("${System.getProperty("user.home")}/Library/Android/sdk"),
        file("/usr/lib/android-sdk")
    ).firstOrNull { it.isDirectory }
    if (sdkDir != null) {
        localProps.writeText("sdk.dir=${sdkDir.absolutePath.replace('\\', '/')}\n")
    }
}

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

rootProject.name = "OpenScan"
include(":app")
