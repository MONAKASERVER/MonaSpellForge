plugins { `java-library` }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.register<JavaExec>("exportMagicApiCatalog") {
    group = "build"
    description = "Generates the VS Code ArcSys magic API catalog from the Java source of truth"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("net.monacraft.spellforge.arcsys.language.ArcMagicApiExporter")
    args(rootProject.file("vscode-extension/src/magicApiCatalog.ts").absolutePath)
}
