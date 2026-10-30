import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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
        // Built against IntelliJ IDEA, the one IDE with Java and Kotlin, plus the Python plugin PyCharm bundles: the
        // impact map reads all three languages. At runtime each language is an optional dependency (plugin.xml).
        intellijIdeaUltimate(providers.gradleProperty("platformVersion").get())
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        plugin("PythonCore", providers.gradleProperty("pythonCoreVersion").get())
        bundledModule("intellij.platform.vcs.impl") // SimpleLocalLineStatusTracker for the review gutter
        pluginVerifier()
        testFramework(TestFrameworkType.Platform) // real PSI for the impact map's language tests
        testFramework(TestFrameworkType.Plugin.Java) // a mock JDK, so Java and Kotlin references resolve in those tests
    }
    testImplementation("junit:junit:4.13.2")
}

// Compile with PyCharm's bundled JBR 25 (no separate JDK needed) but emit Java 21 bytecode, the 2026.1 minimum.
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile> { options.release = 21 }
// SpecFixturesTest reads the editors' shared state examples; a fixture change must re-run it
tasks.test { inputs.dir("../spec") } // the fixtures and the cases the suites run: an edit there alone re-runs the tests
// spec/code-files.json is the editors' shared list of which files are code; the plugin ships it as /spec/code-files.json
tasks.processResources { from("../spec") { include("code-files.json"); into("spec") } }
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
            // The sandbox stays the installed PyCharm, the IDE the screenshots and the prototype are checked in
            providers.gradleProperty("platformPath").orNull?.takeIf { file(it).exists() }?.let { localPath = file(it) }
                ?: run { type = IntelliJPlatformType.PyCharmProfessional; version = providers.gradleProperty("pycharmVersion") }
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
