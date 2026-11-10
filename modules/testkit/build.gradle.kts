val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
    "testImplementation"(libs.findLibrary("postgres-driver").get())
    "testImplementation"(libs.findLibrary("jackson-yaml").get())
}
