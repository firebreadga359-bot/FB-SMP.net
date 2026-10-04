allprojects {
    group = "net.firebreadmc.fbsmp"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(25)
    }
}

tasks.register("assembleServer") {
    dependsOn(":fbsmp-paper:shadowJar", ":fbsmp-velocity:jar")
}