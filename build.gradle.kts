import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.vatsarishabh22"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        local(providers.gradleProperty("platformPath"))
        bundledModule("intellij.platform.vcs.impl") // SimpleLocalLineStatusTracker for the review gutter
    }
    testImplementation("junit:junit:4.13.2")
}

// Compile with PyCharm's bundled JBR 25 (no separate JDK needed) but emit Java 21 bytecode, the 2026.1 minimum.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile> { options.release = 21 }
tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration { ideaVersion { sinceBuild = "261" } }
}

// Dev only: a sandbox with JetBrains' Robot Server on :8082, so UI can be screenshotted and driven while building.
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Didea.trust.all.projects=true",
                    )
                }
            }
            plugins { robotServerPlugin() }
        }
    }
}
