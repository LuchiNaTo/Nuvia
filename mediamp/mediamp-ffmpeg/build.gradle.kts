/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import ffmpeg.configureMediampFfmpegModule
import localcomposite.configureAndroidNamespaceIfPresent
import localcomposite.isLocalCompositeMediamp
import localcomposite.isMediampNativeBuildEnabled
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    `mpp-lib-targets`
    `maven-publish`
}

description = "FFmpeg binary wrapper for MediaMP"
val isLocalComposite = isLocalCompositeMediamp()
val nativeBuildEnabled = !isLocalComposite || isMediampNativeBuildEnabled()

if (!isLocalComposite) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    explicitApi()
    configureAndroidNamespaceIfPresent("org.openani.mediamp.ffmpeg")
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

if (nativeBuildEnabled) {
    if (!isLocalComposite) {
        apply(from = rootProject.file("gradle/publishing/mediamp-ffmpeg-publishing.gradle.kts"))
    }
    configureMediampFfmpegModule()

    if (!isLocalComposite) {
        kotlin {
        targets.withType(KotlinNativeTarget::class.java)
            .matching { target -> target.name == "iosArm64" || target.name == "iosSimulatorArm64" }
            .configureEach {
                val capitalizedTargetName = name.replaceFirstChar { it.uppercase() }
                val frameworkTaskName = "ffmpegAppleFramework$capitalizedTargetName"
                val frameworkSearchPath = project.layout.buildDirectory.dir("apple-framework/$capitalizedTargetName")
                val frameworkSearchPathValue = frameworkSearchPath.get().asFile.absolutePath

                compilations.getByName("main").cinterops.create("mediampffmpegkit") {
                    defFile(project.file("src/nativeInterop/cinterop/mediamp_ffmpegkit.def"))
                    compilerOpts("-F$frameworkSearchPathValue")
                }
                binaries.configureEach {
                    linkerOpts("-F$frameworkSearchPathValue", "-framework", "MediampFFmpegKit")
                }

                if (project.tasks.names.contains(frameworkTaskName)) {
                    project.tasks.named("cinteropMediampffmpegkit$capitalizedTargetName") {
                        dependsOn(frameworkTaskName)
                    }
                    project.tasks.matching { task ->
                        task.name == "compileKotlin$capitalizedTargetName" ||
                            (task.name.startsWith("link") && task.name.endsWith(capitalizedTargetName))
                    }.configureEach {
                        dependsOn(frameworkTaskName)
                    }
                }
            }
        }
    }
}
