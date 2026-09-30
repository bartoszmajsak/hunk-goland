import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.gradle.api.tasks.testing.Test

plugins {
    java
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "dev.hunk"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val localIde = providers.gradleProperty("platformPath")
    .orElse(providers.environmentVariable("GOLAND_HOME"))
val platformVersion = providers.gradleProperty("platformVersion")

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.30.0")

    intellijPlatform {
        pluginVerifier("1.410")
        if (localIde.isPresent) {
            local(localIde)
        } else {
            goland(platformVersion)
        }
        testFramework(
            TestFrameworkType.Starter,
            configurationName = "integrationTestImplementation",
        )
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    "integrationTestImplementation"("org.awaitility:awaitility:4.3.0")
    "integrationTestImplementation"("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    "integrationTestImplementation"("org.jetbrains.teamcity:serviceMessages:2024.07")
    "integrationTestImplementation"("org.kodein.di:kodein-di-jvm:7.26.1")
    "integrationTestImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2")
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
}

fun Test.propagateUiDisplayProperties() {
    systemProperty("hunk.ideVersion", platformVersion.get())
    providers.gradleProperty("uiToolkit").orNull?.let {
        systemProperty("hunk.uiToolkit", it)
    }
    providers.gradleProperty("uiDisplayBackend").orNull?.let { backend ->
        systemProperty("hunk.uiDisplayBackend", backend)
        if (backend == "weston") {
            // IDE rendering happens in the remote JBR process. The local test process only
            // composites its PNGs and must not try to connect to Starter's sentinel X display.
            systemProperty("java.awt.headless", true)
        }
    }
}

intellijPlatformTesting.testIdeUi.register("integrationTest") {
    task {
        propagateUiDisplayProperties()
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            excludeEngines("junit-vintage")
        }
        filter {
            includeTestsMatching("dev.hunk.goland.HunkReviewCommentUiTest")
        }
        systemProperty(
            "hunk.uxReportDir",
            layout.buildDirectory.dir("reports/ux").get().asFile.absolutePath,
        )
    }
}

intellijPlatformTesting.testIdeUi.register("realHunkE2e") {
    task {
        propagateUiDisplayProperties()
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            excludeEngines("junit-vintage")
        }
        filter {
            includeTestsMatching("dev.hunk.goland.HunkRealDaemonE2eTest")
        }
        systemProperty(
            "hunk.uxReportDir",
            layout.buildDirectory.dir("reports/ux").get().asFile.absolutePath,
        )
        systemProperty(
            "hunk.executable",
            providers.gradleProperty("hunkExecutable").orElse("hunk").get(),
        )
    }
}

intellijPlatformTesting.testIdeUi.register("realProjectDiagnostic") {
    task {
        propagateUiDisplayProperties()
        val integrationTestSourceSet = sourceSets.getByName("integrationTest")
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform {
            excludeEngines("junit-vintage")
        }
        filter {
            includeTestsMatching("dev.hunk.goland.HunkRealProjectDiagnosticTest")
        }
        systemProperty(
            "hunk.realProject",
            providers.gradleProperty("hunkRealProject").orNull.orEmpty(),
        )
        systemProperty(
            "hunk.realBase",
            providers.gradleProperty("hunkRealBase").orElse("master").get(),
        )
        systemProperty(
            "hunk.realHead",
            providers.gradleProperty("hunkRealHead")
                .orElse("upstream/llmisvc/feat/lora-regex-routing")
                .get(),
        )
        systemProperty(
            "hunk.realDiagnosticDir",
            providers.gradleProperty("hunkRealDiagnosticDir")
                .orElse(layout.buildDirectory.dir("reports/real-project-diagnostic").map { it.asFile.absolutePath })
                .get(),
        )
        systemProperty(
            "hunk.uxReportDir",
            providers.gradleProperty("hunkRealDiagnosticDir")
                .map { "$it/screenshots" }
                .orElse(layout.buildDirectory.dir("reports/real-project-diagnostic/screenshots").map { it.asFile.absolutePath })
                .get(),
        )
        providers.gradleProperty("hunkDiagnosticPlugin").orNull?.let {
            systemProperty("hunk.diagnosticPlugin", it)
        }
        providers.gradleProperty("hunkDiagnosticWarmupWorkingTree").orNull?.let {
            systemProperty("hunk.diagnosticWarmupWorkingTree", it)
        }
        providers.gradleProperty("hunkDiagnosticConfig").orNull?.let {
            systemProperty("hunk.diagnosticConfig", it)
        }
    }
}

// Starter's remote Driver uses the same default port across isolated IDE profiles.
// Gradle can otherwise schedule independent Test tasks together in a combined invocation.
tasks.named("realHunkE2e") {
    mustRunAfter("integrationTest")
}
tasks.named("realProjectDiagnostic") {
    mustRunAfter("integrationTest", "realHunkE2e")
}

intellijPlatform {
    pluginVerification {
        ides {
            create(IntelliJPlatformType.GoLand, platformVersion.get())
            create(IntelliJPlatformType.GoLand, providers.gradleProperty("verificationPlatformVersion").get())
        }
    }
    pluginConfiguration {
        name = "Hunk Session"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
        }
        vendor {
            name = "Bartosz Majsak"
        }
    }
}
