plugins { base }

subprojects {
    apply(plugin = "java-library")

    group = "dev.forge"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    repositories { mavenCentral() }

    val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies {
        "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-launcher").get())
    }

    // Docker-backed tests carry the "containers" tag. They stay out of `test`
    // (so a clean build works without Docker) and run via `containerTest`.
    tasks.named<Test>("test") {
        useJUnitPlatform { excludeTags("containers") }
    }
    val sourceSets = extensions.getByType<SourceSetContainer>()
    tasks.register<Test>("containerTest") {
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("containers") }
        systemProperty("golden.dir", rootProject.file("golden").absolutePath)
    }
}

// A build that compiles or runs nothing must fail, not pass silently.
val verifyTestsRan by tasks.registering {
    dependsOn(subprojects.map { "${it.path}:test" })
    doLast {
        val empty = subprojects.filter { p ->
            val xml = p.layout.buildDirectory.dir("test-results/test").get().asFile
                .listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            xml == null || xml.isEmpty()
        }
        if (empty.isNotEmpty()) {
            throw GradleException("No test results in: " + empty.joinToString { it.name })
        }
    }
}
tasks.named("check") { dependsOn(verifyTestsRan) }
