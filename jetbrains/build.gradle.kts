import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.vatsarishabh22"
// One version for the build; a release passes its tag (-PpluginVersion=1.2.0)
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        // The installed PyCharm when there is one (no download); CI downloads the same version instead
        val installed = providers.gradleProperty("platformPath").orNull?.takeIf { file(it).exists() }
        if (installed != null) local(installed) else pycharm(providers.gradleProperty("platformVersion").get())
        bundledModule("intellij.platform.vcs.impl") // SimpleLocalLineStatusTracker for the review gutter
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

// Compile with PyCharm's bundled JBR 25 (no separate JDK needed) but emit Java 21 bytecode, the 2026.1 minimum.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile> { options.release = 21 }
// SpecFixturesTest reads the editors' shared state examples; a fixture change must re-run it
tasks.test { inputs.dir("../spec/fixtures") }
tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // use the platform interfaces' own default methods: no compiler-made bridges, which the Plugin Verifier reports
        // as our overriding deprecated and experimental APIs (isApplicable, getAnchor, manage, getPresentation(type), …)
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration { ideaVersion { sinceBuild = "261" } }
    // JetBrains' compatibility check, against the IDEs the README promises
    pluginVerification {
        // verifyIdes in gradle.properties; CI runs one per job (-PverifyIdes=IU:2026.2.3), since all of them overflow a runner's disk
        ides {
            providers.gradleProperty("verifyIdes").get().split(",").forEach {
                val (code, version) = it.trim().split(":")
                create(IntelliJPlatformType.fromCode(code), version)
            }
        }
    }
    // Marketplace release: read from CI secrets, unused until the Marketplace account exists
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing { token = providers.environmentVariable("PUBLISH_TOKEN") }
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
