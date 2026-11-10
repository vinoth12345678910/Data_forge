val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":core"))
    "api"(project(":gen"))
    "implementation"(libs.findLibrary("postgres-driver").get())
    "testImplementation"(project(":schema"))
    "testImplementation"(project(":cli"))
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
    "testImplementation"(libs.findLibrary("jackson-yaml").get())
}
