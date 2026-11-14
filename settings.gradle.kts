rootProject.name = "data-forge"

listOf("core", "schema", "gen", "rules", "load", "cleanup", "cli", "server", "tui", "junit", "testkit")
    .forEach { include(it); project(":$it").projectDir = file("modules/$it") }
