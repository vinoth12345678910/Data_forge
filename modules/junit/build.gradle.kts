val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":core"))
    "api"(project(":schema"))
    "implementation"(project(":gen"))
    "implementation"(project(":rules"))
    "implementation"(project(":load"))
    "api"(platform(libs.findLibrary("junit-bom").get()))
    "api"(libs.findLibrary("junit-jupiter").get())
    "api"(libs.findLibrary("testcontainers-postgres").get())
    "implementation"(libs.findLibrary("postgres-driver").get())
}
