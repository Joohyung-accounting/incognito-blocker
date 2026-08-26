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

rootProject.name = "IncognitoBlocker"
include(":app")

// Build output is placed outside the project directory.
//
// Why: if the project lives inside a synced folder (OneDrive, Dropbox, iCloud)
// the sync client holds handles on files under build/, and Gradle then fails
// with "Unable to delete directory ... packageReleaseResources".
// It also keeps the build path ASCII-only, which avoids a separate class of
// problems on Windows when the project path contains non-ASCII characters.
//
// Delete this block if you would rather have the conventional app/build/ layout.
gradle.beforeProject {
    layout.buildDirectory.set(
        File(System.getProperty("user.home"), ".incognito-build/" + project.name)
    )
}
