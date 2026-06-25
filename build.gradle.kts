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
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.minebench.de/")
    maven("https://jitpack.io")
    mavenCentral()
}

dependencies {
    implementation("ru.arc:arc-core:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-logging:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-redis:1.0-SNAPSHOT")
    implementation("ru.arc:arc-core-velocity:1.0-SNAPSHOT")

    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.gitlab.ruany:LiteBansAPI:0.5.0")

    implementation(kotlin("stdlib"))
    implementation("net.dv8tion:JDA:6.0.0-rc.4") {
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

tasks {
    test {
        useJUnitPlatform()
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

        from({
            val loggingJar =
                project.configurations.getByName("runtimeClasspath").files.first {
                    it.name.startsWith("arc-core-logging")
                }
            zipTree(loggingJar)
        }) {
            include("modules/logging.yml")
        }
        from({
            val redisJar =
                project.configurations.getByName("runtimeClasspath").files.first {
                    it.name.startsWith("arc-core-redis")
                }
            zipTree(redisJar)
        }) {
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
