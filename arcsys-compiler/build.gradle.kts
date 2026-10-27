plugins { `java-library` }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)); withSourcesJar() }

dependencies {
    api(project(":arcsys-language"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
