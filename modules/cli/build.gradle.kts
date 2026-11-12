plugins { application }

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":core"))
    "implementation"(project(":schema"))
    "implementation"(project(":gen"))
    "implementation"(project(":load"))
    "implementation"(project(":rules"))
    "runtimeOnly"(libs.findLibrary("postgres-driver").get())
    "runtimeOnly"(libs.findLibrary("sqlite-driver").get())
    "runtimeOnly"(libs.findLibrary("mysql-driver").get())
    "implementation"(libs.findLibrary("picocli").get())
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
}

application { mainClass.set("forge.cli.Forge") }
