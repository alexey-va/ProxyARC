plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("kapt") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
}

group = "ru.arc"
version = "1.0-SNAPSHOT"
description = "ProxyARC Velocity plugin"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

repositories {
    mavenLocal()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content { includeGroup("ru.ruscrafting.arc") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.minebench.de/")
    maven("https://jitpack.io")
    mavenCentral()
}

dependencies {
    implementation("ru.ruscrafting.arc:arc-core:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-metrics:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-velocity:2.0.0")
    implementation("ru.ruscrafting.arc:arc-core-ai:2.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    testImplementation("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.gitlab.ruany:LiteBansAPI:0.5.0")

    implementation(kotlin("stdlib"))
    implementation("net.dv8tion:JDA:6.5.0") {
        exclude(group = "club.minnced", module = "opus-java")
    }
    implementation("org.telegram:telegrambots:6.9.7.1")
    implementation("org.telegram:telegrambots-abilities:6.9.7.1")
    implementation("redis.clients:jedis:5.2.0-alpha2")
    implementation("org.apache.logging.log4j:log4j-api:2.23.0")
    implementation("org.apache.logging.log4j:log4j-core:2.23.0")
    implementation("com.openai:openai-java:3.5.2")
    implementation("net.kyori:adventure-text-serializer-plain:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("pl.tkowalcz.tjahzi:log4j2-appender-nodep:0.9.17")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.15.2")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.15.2")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val runtimeClasspathConfiguration = configurations.named("runtimeClasspath")
val loggingModuleResources =
    zipTree(
        runtimeClasspathConfiguration.map { configuration ->
            configuration.files.first { it.name.startsWith("arc-core-logging") }
        },
    )
val redisModuleResources =
    zipTree(
        runtimeClasspathConfiguration.map { configuration ->
            configuration.files.first { it.name.startsWith("arc-core-redis") }
        },
    )

tasks {
    test {
        useJUnitPlatform {
            excludeTags("live")
        }
    }

    register<Test>("liveTest") {
        description = "Live OpenRouter integration (requires OPENROUTER_API_KEY)"
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform {
            includeTags("live")
        }
        onlyIf {
            System.getenv("RUN_LIVE_ROUTER_TESTS") == "true" &&
                System.getenv("OPENROUTER_API_KEY")?.isNotBlank() == true
        }
    }

    shadowJar {
        archiveBaseName.set("ProxyARC")
        archiveClassifier.set("")
        archiveVersion.set("")

        mergeServiceFiles()
        transform(
            com.github.jengelman.gradle.plugins.shadow.transformers
                .Log4j2PluginsCacheFileTransformer(),
        )

        exclude("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/NOTICE")

        from(loggingModuleResources) {
            include("modules/logging.yml")
        }
        from(redisModuleResources) {
            include("modules/redis.yml")
        }
    }

    register<Copy>("copyShadowJar") {
        dependsOn(shadowJar)
        from(shadowJar.get().archiveFile)
        into(layout.projectDirectory.dir("ztarget"))
        rename { "ProxyARC.jar" }
    }

    build {
        dependsOn("copyShadowJar")
    }
}
