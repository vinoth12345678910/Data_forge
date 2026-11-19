val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(libs.findLibrary("jackson-yaml").get())
}

// packs/ at the repo root is the single source of the rule packs; they ship inside the jar as /packs/<name>.yml (use: [ecommerce])
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("packs")) {
        include("*.yml")
        into("packs")
    }
}
