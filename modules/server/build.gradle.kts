plugins { application }

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":core"))
    "implementation"(project(":schema"))
    "implementation"(project(":cli"))
    "implementation"(libs.findLibrary("javalin").get())
    "implementation"(libs.findLibrary("jackson-yaml").get())
    "runtimeOnly"(libs.findLibrary("postgres-driver").get())
    "runtimeOnly"(libs.findLibrary("sqlite-driver").get())
    "runtimeOnly"(libs.findLibrary("mysql-driver").get())
}

application { mainClass.set("forge.server.Main") }
