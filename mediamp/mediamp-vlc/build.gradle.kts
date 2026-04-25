/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import localcomposite.isLocalCompositeMediamp

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")

    `mpp-lib-targets`
}

description = "MediaMP backend using VLC"
val isLocalComposite = isLocalCompositeMediamp()
// Nuvio uses vendored MediaMP via local composite build. Publishing is intentionally
// bypassed here to avoid pulling the Vanniktech publishing toolchain into composite mode.

dependencies {
    api(projects.mediampApi)
    api(libs.vlcj)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    testImplementation(kotlin("test"))
}

kotlin {
    explicitApi()
}

