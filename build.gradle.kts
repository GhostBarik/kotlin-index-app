import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.21"
    id("com.github.johnrengelman.shadow") version "6.0.0"
    application
}

group = "com.index"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.microutils:kotlin-logging-jvm:2.0.10")
    implementation("org.slf4j:slf4j-simple:1.8.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.1")
    testImplementation("junit:junit:4.13")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "AppKt"
}

tasks.withType<ShadowJar> {
    archiveFileName.set("library.jar")
}