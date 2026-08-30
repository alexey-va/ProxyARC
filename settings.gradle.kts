rootProject.name = "ProxyARC"

providers.gradleProperty("arcCoreDir").orNull?.let(::file)?.let { arcCoreDir ->
    require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
        "arcCoreDir must point to an arc-core checkout"
    }
    includeBuild(arcCoreDir) {
        dependencySubstitution {
            listOf(
                "arc-core",
                "arc-core-ai",
                "arc-core-logging",
                "arc-core-metrics",
                "arc-core-redis",
                "arc-core-sql",
                "arc-core-velocity",
                "arc-core-integration-testing",
            ).forEach { artifact ->
                substitute(module("ru.ruscrafting.arc:$artifact")).using(project(":$artifact"))
            }
        }
    }
}
