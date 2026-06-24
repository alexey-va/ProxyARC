rootProject.name = "ProxyARC"

val arcCoreDir = sequenceOf(
    file("../../IdeaProjects/arc-core"),
    file("../arc-core"),
).firstOrNull { it.resolve("settings.gradle.kts").isFile }
    ?: error(
        """
        arc-core not found. Clone https://github.com/alexey-va/arc-core next to ProxyARC:
          mcserver:  git submodule add ... arc-core  (../arc-core from ProxyARC)
          IdeaProjects: clone to ~/IdeaProjects/arc-core
        """.trimIndent(),
    )

includeBuild(arcCoreDir)
