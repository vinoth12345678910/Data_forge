import java.util.zip.ZipFile

plugins { application }

val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(project(":core"))
    "implementation"(project(":schema"))
    "implementation"(project(":gen"))
    "implementation"(project(":load"))
    "implementation"(project(":cleanup"))
    "implementation"(project(":rules"))
    "runtimeOnly"(libs.findLibrary("postgres-driver").get())
    "runtimeOnly"(libs.findLibrary("sqlite-driver").get())
    "runtimeOnly"(libs.findLibrary("mysql-driver").get())
    "implementation"(libs.findLibrary("picocli").get())
    "testImplementation"(libs.findLibrary("testcontainers-postgres").get())
    "testImplementation"(libs.findLibrary("testcontainers-junit").get())
}

application { mainClass.set("forge.cli.Forge") }

// The fat jar (forge-<version>.jar): this module, every project and library it runs with, and merged service files, so
// all three JDBC drivers stay registered (a plain merge keeps only one META-INF/services/java.sql.Driver).
val fatServices = layout.buildDirectory.dir("fat-services")

val mergeServices = tasks.register("mergeServices") {
    val cp = configurations.named("runtimeClasspath")
    inputs.files(cp)
    outputs.dir(fatServices)
    doLast {
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        cp.get().filter { it.name.endsWith(".jar") }.forEach { jar ->
            ZipFile(jar).use { z ->
                z.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }.forEach { e ->
                    val lines = z.getInputStream(e).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                    merged.getOrPut(e.name) { linkedSetOf() }.addAll(lines)
                }
            }
        }
        val out = fatServices.get().asFile
        out.deleteRecursively()
        merged.forEach { (name, lines) ->
            val f = File(out, name)
            f.parentFile.mkdirs()
            f.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("forge")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    manifest { attributes("Main-Class" to "forge.cli.Forge", "Implementation-Version" to project.version.toString()) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(mergeServices)
    from(project.extensions.getByType(JavaPluginExtension::class.java).sourceSets.getByName("main").output)
    from(fatServices)
    from({ configurations.getByName("runtimeClasspath").filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/services/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF",
            "module-info.class", "META-INF/versions/*/module-info.class")
    }
}
