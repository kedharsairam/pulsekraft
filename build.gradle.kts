// Top-level build file. Module config lives in app/build.gradle.kts.
//
// Versions are pinned to the same toolchain as the rest of this
// workspace, deliberately. An app that shares a build system with its
// siblings can be checked out next to them and built without a toolchain
// dance, and "works on my machine" is a bug class, not a personality.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
