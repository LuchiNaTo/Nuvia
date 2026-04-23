/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import localcomposite.configureAndroidNamespaceIfPresent
import localcomposite.isLocalCompositeMediamp

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
    kotlin("plugin.serialization")
}

description = "Core API for MediaMP"
val isLocalComposite = isLocalCompositeMediamp()
val jvmTestSourceSetName = if (isLocalComposite) "desktopTest" else "jvmTest"

if (!isLocalComposite) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}


kotlin {
    explicitApi()
    configureAndroidNamespaceIfPresent("org.openani.mediamp.api")
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.io.core) // TODO: 2024/12/16 remove 
            compileOnly(libs.androidx.annotation)
            api(libs.kotlinx.coroutines.core)
            api(compose.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName(jvmTestSourceSetName).dependencies {
            implementation(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
            implementation(libs.androidx.annotation)
            implementation(projects.mediampInternalUtils)
        }
        if (!isLocalComposite) {
            androidMain.dependencies {
                api(libs.androidx.annotation)
            }
        }
    }
}

if (!isLocalComposite) {
    apply(from = rootProject.file("gradle/publishing/mediamp-api-publishing.gradle.kts"))
}
