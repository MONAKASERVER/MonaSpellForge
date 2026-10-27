pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

rootProject.name = "MonaSpellForge"
include("shared", "paper", "fabric", "arcsys-language", "arcsys-compiler", "arcsys-vm", "arcsys-cli")
