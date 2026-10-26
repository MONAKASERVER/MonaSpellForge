plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }

dependencies {
    implementation(project(":shared"))
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    implementation("org.xerial:sqlite-jdbc:3.51.1.0")
    implementation("org.yaml:snakeyaml:2.4")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.1.2.build.74-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.shadowJar {
    archiveBaseName.set("MonaSpellForge-Paper")
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build { dependsOn(tasks.shadowJar) }
