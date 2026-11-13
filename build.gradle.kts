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
        useJUnitPlatform { excludeTags("containers", "scale") }
        systemProperty("golden.dir", rootProject.file("golden").absolutePath)
    }
    val sourceSets = extensions.getByType<SourceSetContainer>()
    tasks.register<Test>("containerTest") {
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("containers") }
        // The frozen Phase 5 safety tests run in their own step (safetyTest, verify.sh step i), per feature.
        filter {
            excludeTestsMatching("forge.safety.*")
            isFailOnNoMatchingTests = false
        }
        systemProperty("golden.dir", rootProject.file("golden").absolutePath)
        // Docker and golden files are inputs Gradle cannot see: never skip as up-to-date.
        outputs.upToDateWhen { false }
    }
    // The frozen Phase 5 safety tests (modules/testkit/src/test/java/forge/safety). scripts/safety-green.txt lists the
    // ones a branch must pass; scripts/verify.sh step i runs exactly those, and the full suite at the end of Phase 5.
    tasks.register<Test>("safetyTest") {
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("containers") }
        filter {
            includeTestsMatching("forge.safety.*")
            isFailOnNoMatchingTests = false
        }
        systemProperty("golden.dir", rootProject.file("golden").absolutePath)
        outputs.upToDateWhen { false }
    }
    // The scale tests (one million rows, minutes long) carry the "scale" tag and run via `scaleTest` (verify.sh step h).
    tasks.register<Test>("scaleTest") {
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform { includeTags("scale") }
        systemProperty("golden.dir", rootProject.file("golden").absolutePath)
        outputs.upToDateWhen { false }
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
