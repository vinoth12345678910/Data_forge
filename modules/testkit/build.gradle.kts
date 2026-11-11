val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":core"))
    "api"(project(":gen"))
    "api"(project(":load"))
    "implementation"(libs.findLibrary("postgres-driver").get())
    "testImplementation"(project(":schema"))
    "testImplementation"(project(":cli"))
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
    "testImplementation"(libs.findLibrary("jackson-yaml").get())
    "testImplementation"(libs.findLibrary("jqwik").get())
}

// Child-JVM tests (loader memory) need the test classpath as a plain string.
tasks.withType<Test>().configureEach {
    val cp = project.extensions.getByType<SourceSetContainer>()["test"].runtimeClasspath
    doFirst { systemProperty("forge.test.classpath", cp.asPath) }
}
