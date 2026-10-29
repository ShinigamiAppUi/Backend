plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    application
}

import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "shinigami"
version = "1.0.0"

application {
    mainClass.set("streamix.api.BackendMainKt")
}

// The VPS runtime is Java 17. Keep the backend artifact compatible with it
// even when the GitHub runner itself uses a newer JDK.
kotlin { jvmToolchain(17) }

val migratedSourceTree = tasks.register<Sync>("prepareMigratedSourceTree") {
    into(layout.buildDirectory.dir("generated/migratedSources"))
    // backend/sources is the canonical migrated provider tree. backend/oce is
    // the legacy duplicate boundary tree and must not be compiled alongside it.
    from(project.fileTree(project.projectDir.resolve("sources")) { include("**/*.kt"); exclude("**/src/test/**") })
}

val migratedResourceTree = tasks.register<Sync>("prepareMigratedResources") {
    into(layout.buildDirectory.dir("generated/migratedResources"))
    // Keep resources aligned with the same canonical migrated source tree.
    from(project.fileTree(project.projectDir.resolve("sources")) { include("**/*.json"); exclude("**/src/test/**") })
}

kotlin {
    sourceSets {
        getByName("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/migratedSources"))
            resources.srcDir(layout.buildDirectory.dir("generated/migratedResources"))
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    dependsOn(migratedSourceTree)
    dependsOn(migratedResourceTree)
}

tasks.named("processResources") {
    dependsOn(migratedResourceTree)
}

tasks.test {
    useJUnitPlatform()
    exclude("**/streamix/e2e/**")
    exclude("**/streamix/runtime/A002LiveProviderStreamTest.class")
    exclude("**/streamix/runtime/AnimeinApiLiveSmokeTest.class")
    exclude("**/streamix/runtime/A011RuntimeProviderExpansionLiveTest.class")
    // Live upstream checks belong only to liveE2eTest; they must not make deterministic JVM CI flaky.
    exclude("**/streamix/api/AniListLiveConnectivityTest.class")
    exclude("**/streamix/runtime/AnimeinOlderEpisodeLiveTest.class")
    exclude("**/streamix/runtime/AnimeinSingleDigitEpisodeLiveTest.class")
}

val liveE2eTest = tasks.register<Test>("liveE2eTest") {
    description = "Runs live JVM provider pool E2E against the configured public providers."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(tasks.named("testClasses"))
    include("**/streamix/runtime/A002LiveProviderStreamTest.class", "**/streamix/runtime/AnimeinApiLiveSmokeTest.class", "**/streamix/runtime/A011RuntimeProviderExpansionLiveTest.class", "**/streamix/api/AniListLiveConnectivityTest.class", "**/streamix/runtime/AnimeinOlderEpisodeLiveTest.class", "**/streamix/runtime/AnimeinSingleDigitEpisodeLiveTest.class")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    shouldRunAfter(tasks.test)
}

val deploymentLiveE2eTest = tasks.register<Test>("deploymentLiveE2eTest") {
    description = "Runs the minimal live provider runtime smoke required before VPS deployment."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn(tasks.named("testClasses"))
    include("**/streamix/e2e/DeploymentRuntimeLiveE2ETest.class")
    testLogging {
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    shouldRunAfter(tasks.test)
}

val streamixCoreDependency = if (project.path == ":backend") {
    project(":backend:streamix-core")
} else {
    project(":streamix-core")
}

dependencies {
    implementation(streamixCoreDependency)
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jsoup:jsoup:1.22.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.mozilla:rhino:1.8.1")
    implementation("org.json:json:20260814")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jspecify:jspecify:1.0.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.register<JavaExec>("backendRun") {
    group = "application"
    description = "Runs the Shinigami backend HTTP server."
    mainClass.set("streamix.api.BackendMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    dependsOn(tasks.named("classes"))
}
