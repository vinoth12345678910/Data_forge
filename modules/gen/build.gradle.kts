val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":core"))
    "testImplementation"(project(":schema"))
    "testImplementation"(libs.findLibrary("jqwik").get())
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
}

// Child-JVM determinism tests need the test classpath as a plain string.
tasks.withType<Test>().configureEach {
    val cp = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    doFirst { systemProperty("forge.test.classpath", cp.asPath) }
}
