plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// This project lives under a Google Drive sync folder (G:\...). Google
// Drive's virtual filesystem driver fights with Gradle over file handles in
// the high-churn build/ output directory, causing intermittent
// AccessDeniedException failures. Route build outputs to a local, unsynced
// folder instead -- only the source stays in the synced Drive folder.
val localBuildRoot = File("C:/RoverMEMS-build")
layout.buildDirectory.set(File(localBuildRoot, "root"))
subprojects {
    layout.buildDirectory.set(File(localBuildRoot, project.name))
}
