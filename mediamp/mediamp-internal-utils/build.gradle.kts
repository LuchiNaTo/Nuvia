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

    `mpp-lib-targets`
    kotlin("plugin.serialization")
}

description = "MediaMP Internal Utils"
val isLocalComposite = isLocalCompositeMediamp()
val jvmTestSourceSetName = if (isLocalComposite) "desktopTest" else "jvmTest"

if (!isLocalComposite) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}


kotlin {
    configureAndroidNamespaceIfPresent("org.openani.mediamp.internal.utils")
    sourceSets {
        commonMain.dependencies {
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName(jvmTestSourceSetName).dependencies {
            implementation(libs.junit)
        }
        desktopMain.dependencies {
        }
        iosMain.dependencies {
        }
        if (!isLocalComposite) {
            androidMain.dependencies {
            }
        }
    }
}

if (!isLocalComposite) {
    apply(from = rootProject.file("gradle/publishing/mediamp-internal-utils-publishing.gradle.kts"))
}
