plugins { application }

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
application { mainClass.set("net.monacraft.spellforge.arcsys.cli.ArcSysCli") }

dependencies { implementation(project(":arcsys-compiler")) }
