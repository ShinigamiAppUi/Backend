plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
}

group = "shinigami"
version = "1.0.0"

kotlin { jvmToolchain(17) }

tasks.jar {
    archiveBaseName.set("shinigami-core")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jsoup:jsoup:1.22.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test { useJUnitPlatform() }
