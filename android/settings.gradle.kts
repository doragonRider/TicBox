pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Kadb's SPAKE2 pairing dependency is published by its maintainer on JitPack.
        maven("https://jitpack.io") { content { includeModule("com.github.Flyfish233", "spake2-java") } }
    }
}
rootProject.name = "TicBox"
include(":app", ":ui", ":demo", ":core:api", ":core:protocol", ":core:engine", ":core:platform")
include(":watch:healthbridge") // A watch installation; :app remains the only phone APK.
include(":watch:toolboxagent") // The watch APK carries the same agent for both shell and root.
include(":watch:healthmodule") // Independent optional Magisk/Zygisk module; never part of a phone/watch APK.
include(":thirdparty:kadb") // Pinned Android sources with the platform SSLSocket transport.
