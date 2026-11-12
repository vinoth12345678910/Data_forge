val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":core"))
    "api"(project(":gen"))
    "implementation"(project(":schema"))
    "implementation"(libs.findLibrary("postgres-driver").get())
}
