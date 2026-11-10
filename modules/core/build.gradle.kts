val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("jackson-yaml").get())
}
