plugins { application }

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":server"))
    "implementation"(project(":cli"))
    "implementation"(libs.findLibrary("jackson-yaml").get())
    "testImplementation"(project(":core"))
    "testImplementation"(project(":schema"))
}

application { mainClass.set("forge.tui.Tui") }

// `gradlew :tui:run` must pass the terminal's input through, or the menu reads nothing and quits.
tasks.named<JavaExec>("run") { standardInput = System.`in` }
