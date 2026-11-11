plugins { antlr }

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "antlr"(libs.findLibrary("antlr-tool").get())
    "api"(project(":core"))
    "implementation"(libs.findLibrary("antlr-runtime").get())
    "implementation"(libs.findLibrary("postgres-driver").get())
    "testImplementation"(project(":schema"))
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
}

tasks.named<AntlrTask>("generateGrammarSource") {
    arguments = arguments + listOf("-visitor", "-no-listener", "-package", "forge.rules.parse")
    outputDirectory = file("${layout.buildDirectory.get().asFile}/generated-src/antlr/main/forge/rules/parse")
}
