allprojects {
    group = "net.monacraft.spellforge"
    version = "2.0.0-alpha.1"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://jitpack.io")
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Keep CI and Windows developer machines from reserving a 512 MiB G1 heap per test worker.
        maxHeapSize = "128m"
        jvmArgs("-XX:+UseSerialGC")
    }
}

tasks.register("dist") {
    dependsOn(":paper:shadowJar", ":fabric:remapJar", ":arcsys-cli:distZip")
}
