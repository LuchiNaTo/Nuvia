/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `kotlin-dsl`
}

fun readLocalCompositeFlag(): String? {
    val propertiesFile = rootDir.parentFile.resolve("gradle.properties")
    if (!propertiesFile.isFile) return null
    val properties = Properties()
    propertiesFile.inputStream().use { stream ->
        properties.load(stream)
    }
    return properties.getProperty("mediamp.localComposite")
}

val isLocalComposite = (
    providers.gradleProperty("mediamp.localComposite").orNull
        ?: readLocalCompositeFlag()
    )?.toBooleanStrictOrNull() ?: false

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") // Compose Multiplatform pre-release versions
}

sourceSets.named("main") {
    if (isLocalComposite) {
        java.exclude("publishing.kt")
    }
}

tasks.withType(KotlinCompile::class.java).configureEach {
    if (isLocalComposite) {
        exclude("publishing.kt")
        exclude("**/publishing.kt")
    }
}

kotlin {
    jvmToolchain {
        this.languageVersion = JavaLanguageVersion.of(17)
    }
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi")
    }
}

dependencies {
    api(gradleApi())
    api(gradleKotlinDsl())

    api(libs.kotlin.gradle.plugin) {
        exclude("org.jetbrains.kotlin", "kotlin-stdlib")
        exclude("org.jetbrains.kotlin", "kotlin-stdlib-common")
        exclude("org.jetbrains.kotlin", "kotlin-reflect")
    }

    api(libs.android.gradle.plugin)
    api(libs.android.library.gradle.plugin)
    api(libs.android.kotlin.multiplatform.library.gradle.plugin)
    api(libs.compose.multiplatfrom.gradle.plugin)
    api(libs.kotlin.compose.compiler.gradle.plugin)
    if (!isLocalComposite) {
        api(libs.gradle.maven.publish.plugin)
    }
    implementation(kotlin("script-runtime"))
}
