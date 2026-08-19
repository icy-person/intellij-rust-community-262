import groovy.xml.XmlParser
import org.gradle.api.JavaVersion.VERSION_21
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.jetbrains.intellij.platform.gradle.Constants.Configurations
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jsoup.Jsoup
import java.io.Writer
import kotlin.concurrent.thread

// The same as --stacktrace param
gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS

val isCI = System.getenv("CI") != null
val isTeamcity = System.getenv("TEAMCITY_VERSION") != null

val channel = prop("publishChannel")
val platformVersion = prop("platformVersion").toInt()
val baseIDE = prop("baseIDE")
val ideToRun = prop("ideToRun").ifEmpty { baseIDE }
val ideaVersion = prop("ideaVersion")
val baseVersion = versionForIde(baseIDE)

// Bundled plugins extracted from IntelliJ Platform
//
// https://plugins.jetbrains.com/docs/intellij/api-changes-list-2024.html#json-plugin-new-20243
val jsonPlugin = "com.intellij.modules.json"
// https://blog.jetbrains.com/grazie/2025/10/grazie-s-next-step-built-in-language-intelligence-for-your-ide/
// https://plugins.jetbrains.com/docs/intellij/spell-checking.html#grammar-checks
val graziePlugin = "tanvd.grazi"

// TOML is a separately published plugin on the 2026.2 platform.
val tomlPlugin = "org.toml.lang:262.9437.22"
val psiViewerPlugin: String by project
val copyrightPlugin = "com.intellij.copyright"
val javaPlugin = "com.intellij.java"
val javaIdePlugin = "com.intellij.java.ide"
val javaScriptPlugin = "JavaScript"

val compileNativeCodeTaskName = "compileNativeCode"

val grammarKitFakePsiDeps = "grammar-kit-fake-psi-deps"

val basePluginArchiveName = "intellij-rust"

plugins {
    idea
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
    id("org.jetbrains.grammarkit") version "2023.3.0.2"
    id("net.saliman.properties") version "1.5.2"
    id("org.gradle.test-retry") version "1.6.2"
}

idea {
    module {
        // https://github.com/gradle/kotlin-dsl/issues/537/
        excludeDirs = excludeDirs + file("testData") + file("deps") + file("bin") +
            file(".intellijPlatform") + file("$grammarKitFakePsiDeps/src/main/kotlin")
    }
}

allprojects {
    apply {
        plugin("idea")
        plugin("kotlin")
        plugin("org.jetbrains.grammarkit")
        plugin("org.jetbrains.intellij.platform")
        plugin("org.gradle.test-retry")
    }

    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }

    idea {
        module {
            generatedSourceDirs.add(file("src/gen"))
        }
    }

    intellijPlatform {
        sandboxContainer.set(layout.buildDirectory.dir("$ideToRun-sandbox-$platformVersion"))
        pluginConfiguration {
            ideaVersion {
                sinceBuild.set(prop("sinceBuild"))
                untilBuild.set(prop("untilBuild"))
            }
        }

        pluginVerification {
            ides {
                recommended()
            }
        }
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = VERSION_21
        targetCompatibility = VERSION_21
    }

    tasks {
        withType<KotlinCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                languageVersion.set(KotlinVersion.DEFAULT)
                apiVersion.set(KotlinVersion.KOTLIN_2_1)
                freeCompilerArgs.set(listOf("-Xjvm-default=all"))
            }
        }

        runIde { enabled = false }
        prepareSandbox { enabled = false }
        buildSearchableOptions { enabled = false }
        prepareJarSearchableOptions { enabled = false }
        jarSearchableOptions { enabled = false }

        test {
            systemProperty("java.awt.headless", "true")
            testLogging {
                showStandardStreams = prop("showStandardStreams").toBoolean()
                afterSuite(
                    KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
                        if (desc.parent == null) {
                            val output = "Results: ${result.resultType} (${result.testCount} tests, ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped)"
                            println(output)
                        }
                    })
                )
            }
            if (isCI) {
                retry {
                    maxRetries.set(3)
                    maxFailures.set(5)
                }
            }
        }

        if (project.name in listOf("intellij-rust", "plugin")) {
            register<Exec>(compileNativeCodeTaskName) {
                workingDir = rootDir.resolve("native-helper")
                executable = "cargo"
                environment("RUSTC_BOOTSTRAP", "1")

                val hostPlatform = DefaultNativePlatform.host()
                val archName = when (val archName = hostPlatform.architecture.name) {
                    "arm-v8", "aarch64" -> "arm64"
                    else -> archName
                }
                val outDir = "${rootDir}/bin/${hostPlatform.operatingSystem.toFamilyName()}/$archName"
                args("build", "--release", "-Z", "unstable-options", "--out-dir", outDir)
                enabled = prop("compileNativeCode").toBoolean()
            }
        }
    }

    sourceSets {
        main {
            java.srcDirs("src/gen")
            resources.srcDirs("src/$platformVersion/main/resources")
        }
        test {
            resources.srcDirs("src/$platformVersion/test/resources")
        }
    }
    kotlin {
        sourceSets {
            main {
                kotlin.srcDirs("src/$platformVersion/main/kotlin")
            }
            test {
                kotlin.srcDirs("src/$platformVersion/test/kotlin")
            }
        }
    }

    val testOutput = configurations.create("testOutput")

    dependencies {
        intellijPlatform {
            if (baseIDE == "IC") {
                intellijIdea(ideaVersion) { useCache = true }
            } else {
                create(baseIDE, baseVersion) { useCache = true }
            }

            pluginVerifier()
            testFramework(TestFrameworkType.Platform, configurationName = Configurations.INTELLIJ_PLATFORM_DEPENDENCIES)

            bundledPlugins(
                listOf(
                    jsonPlugin,
                    graziePlugin
                )
            )

            bundledModule("intellij.platform.coverage")
            bundledModule("intellij.platform.coverage.agent")
            bundledModule("intellij.platform.vcs.impl")
            bundledModule("intellij.platform.vcs.impl.shared")
            bundledModule("intellij.spellchecker")

            testBundledModule("intellij.platform.navbar")
            testBundledModule("intellij.platform.navbar.backend")
            testBundledModule("intellij.platform.vcs.impl.lang")
        }

        compileOnly(kotlin("stdlib-jdk8"))
        implementation("junit:junit:4.13.2")
        testImplementation("org.opentest4j:opentest4j:1.3.0")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
        testOutput(sourceSets.getByName("test").output.classesDirs)
    }

    afterEvaluate {
        tasks.withType<AbstractTestTask> {
            testLogging {
                if (hasProp("showTestStatus") && prop("showTestStatus").toBoolean()) {
                    events = setOf(TestLogEvent.STARTED, TestLogEvent.PASSED, TestLogEvent.SKIPPED, TestLogEvent.FAILED)
                }
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        tasks.withType<Test>().configureEach {
            jvmArgs = listOf("-Xmx3g", "-XX:-OmitStackTraceInFastThrow", "-XX:SoftRefLRUPolicyMSPerMB=50")
            systemProperty("jna.nosys", "true")
            systemProperty(
                "java.util.concurrent.ForkJoinPool.common.threadFactory",
                "com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory"
            )
            if (isTeamcity) {
                ignoreFailures = true
            }
            if (hasProp("excludeTests")) {
                exclude(prop("excludeTests"))
            }
        }
    }
}

val Project.dependencyCachePath
    get(): String {
        val cachePath = file("${rootProject.projectDir}/deps")
        if (!cachePath.exists()) {
            cachePath.mkdirs()
        }
        return cachePath.absolutePath
    }

val pluginProjects: List<Project>
    get() = rootProject.allprojects.filter { it.name != grammarKitFakePsiDeps }

project(":plugin") {
    version = System.getenv("BUILD_NUMBER") ?: "${platformVersion}.${prop("buildNumber")}"

    intellijPlatform {
        pluginConfiguration {
            description.set(provider { file("description.html").readText() })
        }
    }

    dependencies {
        intellijPlatform {
            val pluginList = mutableListOf(
                tomlPlugin,
                psiViewerPlugin,
            )
            val bundledPluginList = mutableListOf(
                javaScriptPlugin
            )
            if (ideToRun in setOf("IU", "IC")) {
                bundledPluginList += listOf(
                    copyrightPlugin,
                    javaPlugin,
                )
            }
            plugins(pluginList)
            bundledPlugins(bundledPluginList)

            pluginComposedModule(implementation(project(":idea")))
            pluginComposedModule(implementation(project(":copyright")))
            pluginComposedModule(implementation(project(":coverage")))
            pluginComposedModule(implementation(project(":duplicates")))
            pluginComposedModule(implementation(project(":grazie")))
            pluginComposedModule(implementation(project(":js")))
            pluginComposedModule(implementation(project(":ml-completion")))
        }

        implementation(project(":"))
    }

    val createSourceJar = tasks.register<Jar>("createSourceJar") {
        dependsOn(":generateLexer")
        dependsOn(":generateParser")

        for (prj in pluginProjects) {
            from(prj.kotlin.sourceSets.main.get().kotlin) {
                include("**/*.java")
                include("**/*.kt")
            }
        }

        destinationDirectory.set(layout.buildDirectory.dir("libs"))
        archiveBaseName.set(basePluginArchiveName)
        archiveClassifier.set("src")
    }

    tasks {
        buildPlugin {
            dependsOn(createSourceJar)
            from(createSourceJar) { into("lib/src") }
            archiveBaseName.set(basePluginArchiveName)
        }
        runIde { enabled = true }
        prepareSandbox {
            enabled = true
        }
        verifyPlugin {
        }
        buildSearchableOptions {
            enabled = prop("enableBuildSearchableOptions").toBoolean()
        }
        prepareJarSearchableOptions {
            enabled = prop("enableBuildSearchableOptions").toBoolean()
        }
        jarSearchableOptions {
            enabled = prop("enableBuildSearchableOptions").toBoolean()
        }
        withType<PrepareSandboxTask> {
            dependsOn(named(compileNativeCodeTaskName))
            from("${rootDir}/bin") {
                into("${intellijPlatform.projectName.get()}/bin")
                include("**")
            }
        }
        withType<RunIdeTask> {
            jvmArgs("-Xmx768m", "-XX:+UseG1GC", "-XX:SoftRefLRUPolicyMSPerMB=50")
            jvmArgs("-Didea.auto.reload.plugins=false")
            jvmArgs("-Dide.show.tips.on.startup.default.value=false")
        }
        withType<PublishPluginTask> {
            token.set(prop("publishToken"))
            channels.set(listOf(channel))
        }
    }

    tasks.register<RunIdeTask>("buildEventsScheme") {
        dependsOn(tasks.prepareSandbox)
        args("buildEventsScheme", "--outputFile=${layout.buildDirectory.get().asFile.resolve("eventScheme.json").absolutePath}", "--pluginId=org.rust.lang")
        environment("IDEA_BUILD_NUMBER", "253")
    }
}

project(":$grammarKitFakePsiDeps")

project(":") {
    sourceSets {
        main {
            if (channel == "nightly" || channel == "dev") {
                resources.srcDirs("src/main/resources-nightly")
                resources.srcDirs("src/$platformVersion/main/resources-nightly")
            } else {
                resources.srcDirs("src/main/resources-stable")
                resources.srcDirs("src/$platformVersion/main/resources-stable")
            }
        }
    }
}

project(":duplicates") {
    tasks {
        withType<JavaCompile>().configureEach {
            options.compilerArgs.add("-Xlint:-removal")
        }
    }
}

fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String =
    extra.properties[name] as? String
        ?: error("Property `$name` is not defined in gradle.properties")

fun versionForIde(ideName: String): String = when (ideName) {
    "IU", "IC" -> ideaVersion
    else -> error("Unexpected IDE name: `$baseIDE`")
}

inline operator fun <T : Task> T.invoke(a: T.() -> Unit): T = apply(a)

fun String.execute(wd: String? = null, ignoreExitCode: Boolean = false, print: Boolean = true): String =
    split(" ").execute(wd, ignoreExitCode, print)
