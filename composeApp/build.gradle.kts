import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import java.io.File
import java.util.Properties
import javax.inject.Inject

abstract class GenerateRuntimeConfigsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Optional
    @get:InputFile
    abstract val localPropertiesFile: RegularFileProperty

    @get:Input
    abstract val appVersionName: Property<String>

    @get:Input
    abstract val appVersionCode: Property<Int>

    @TaskAction
    fun generate() {
        val props = Properties()
        localPropertiesFile.asFile.orNull?.takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

        val outDir = outputDir.get().asFile
        outDir.resolve("com/nuvio/app/core/network").apply {
            mkdirs()
            resolve("SupabaseConfig.kt").writeText(
                """
                |package com.nuvio.app.core.network
                |
                |object SupabaseConfig {
                |    const val URL = "${props.getProperty("SUPABASE_URL", "")}" 
                |    const val ANON_KEY = "${props.getProperty("SUPABASE_ANON_KEY", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/tmdb/TmdbConfig.kt").delete()

        outDir.resolve("com/nuvio/app/features/trakt").apply {
            mkdirs()
            resolve("TraktConfig.kt").writeText(
                """
                |package com.nuvio.app.features.trakt
                |
                |object TraktConfig {
                |    const val CLIENT_ID = "${props.getProperty("TRAKT_CLIENT_ID", "")}" 
                |    const val CLIENT_SECRET = "${props.getProperty("TRAKT_CLIENT_SECRET", "")}" 
                |    const val REDIRECT_URI = "${props.getProperty("TRAKT_REDIRECT_URI", "nuvio://auth/trakt")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/player/skip").apply {
            mkdirs()
            resolve("IntroDbConfig.kt").writeText(
                """
                |package com.nuvio.app.features.player.skip
                |
                |object IntroDbConfig {
                |    const val URL = "${props.getProperty("INTRODB_API_URL", "")}" 
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/core/build").apply {
            mkdirs()
            resolve("AppVersionConfig.kt").writeText(
                """
                |package com.nuvio.app.core.build
                |
                |object AppVersionConfig {
                |    const val VERSION_NAME = "${appVersionName.get()}"
                |    const val VERSION_CODE = ${appVersionCode.get()}
                |}
                """.trimMargin()
            )
        }

        outDir.resolve("com/nuvio/app/features/settings").apply {
            mkdirs()
            resolve("CommunityConfig.kt").writeText(
                """
                |package com.nuvio.app.features.settings
                |
                |object CommunityConfig {
                |    const val DONATIONS_BASE_URL = "${props.getProperty("DONATIONS_BASE_URL", "")}" 
                |    const val DONATIONS_DONATE_URL = "${props.getProperty("DONATIONS_DONATE_URL", "")}" 
                |}
                """.trimMargin()
            )
        }
    }
}

fun readXcconfigValue(file: File, key: String): String? {
    if (!file.exists()) return null
    return file.readLines()
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .map { line ->
            val separatorIndex = line.indexOf('=')
            line.substring(0, separatorIndex).trim() to line.substring(separatorIndex + 1).trim()
        }
        .firstOrNull { (entryKey, _) -> entryKey == key }
        ?.second
}

fun resolveWindowsRuntimeInputDir(project: Project): File {
    val overridePath = project.providers.gradleProperty("nuvio.windowsRuntimeDir").orNull
        ?: System.getenv("NUVIO_WINDOWS_RUNTIME_DIR")
    return overridePath?.let(project::file) ?: project.file("vendor-runtime/windows-x64")
}

fun normalizeWindowsPackageVersion(versionName: String, versionCode: Int): String {
    val numericSegments = versionName
        .split('.', '-', '_')
        .mapNotNull(String::toIntOrNull)
        .take(4)
        .toMutableList()

    if (numericSegments.isEmpty()) {
        return versionCode.toString()
    }

    while (numericSegments.size < 3) {
        numericSegments += 0
    }

    return numericSegments.joinToString(".")
}

abstract class PrepareWindowsRuntimeTask : DefaultTask() {
    @get:Internal
    abstract val inputDir: DirectoryProperty

    @get:Input
    abstract val inputDirPath: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun prepare() {
        val inputDirectory = inputDir.get().asFile
        if (!inputDirectory.isDirectory) {
            error(
                "Windows runtime directory not found at '${inputDirectory.absolutePath}'. " +
                    "Place mediamp runtime JARs in composeApp/vendor-runtime/windows-x64 " +
                    "or set -Pnuvio.windowsRuntimeDir / NUVIO_WINDOWS_RUNTIME_DIR.",
            )
        }

        val runtimeJars = inputDirectory.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            ?.sortedBy(File::getName)
            .orEmpty()

        if (runtimeJars.isEmpty()) {
            error(
                "No Windows runtime JARs found in '${inputDirectory.absolutePath}'. " +
                    "Bundle the mediamp runtime JARs before running or packaging the desktop app.",
            )
        }

        val outputDirectory = outputDir.get().asFile
        fileSystemOperations.delete {
            delete(outputDirectory)
        }
        fileSystemOperations.copy {
            from(runtimeJars)
            into(outputDirectory)
        }
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

val supabaseProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseStoreFile = supabaseProps.getProperty("NUVIO_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword = supabaseProps.getProperty("NUVIO_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = supabaseProps.getProperty("NUVIO_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = supabaseProps.getProperty("NUVIO_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeystore = releaseStoreFile?.let(rootProject::file)
val appVersionConfigFile = rootProject.file("iosApp/Configuration/Version.xcconfig")
val releaseAppVersionName = readXcconfigValue(appVersionConfigFile, "MARKETING_VERSION")
    ?: error("MARKETING_VERSION is missing from ${appVersionConfigFile.path}")
val releaseAppVersionCode = readXcconfigValue(appVersionConfigFile, "CURRENT_PROJECT_VERSION")
    ?.toIntOrNull()
    ?: error("CURRENT_PROJECT_VERSION is missing or invalid in ${appVersionConfigFile.path}")
val iosDistribution = (
    providers.gradleProperty("nuvio.ios.distribution").orNull
        ?: System.getenv("NUVIO_IOS_DISTRIBUTION")
        ?: supabaseProps.getProperty("NUVIO_IOS_DISTRIBUTION")
        ?: "appstore"
    ).trim().lowercase()
require(iosDistribution == "appstore" || iosDistribution == "full") {
    "NUVIO_IOS_DISTRIBUTION must be 'appstore' or 'full'."
}
val iosDistributionSourceDir = if (iosDistribution == "full") {
    "src/iosFull/kotlin"
} else {
    "src/iosAppStore/kotlin"
}
val iosFrameworkBundleId = "com.nuvio.media"
val fullCommonSourceDir = project.file("src/fullCommonMain/kotlin")
val generatedRuntimeConfigDir = layout.buildDirectory.dir("generated/runtime-config/kotlin")
val windowsRuntimeInputDir = resolveWindowsRuntimeInputDir(project)
val preparedWindowsRuntimeDir = layout.buildDirectory.dir("vendor-runtime/windows-x64")
val java21Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val java21Home = java21Launcher.map { it.metadata.installationPath.asFile.absolutePath }
val windowsPackageVersion = normalizeWindowsPackageVersion(releaseAppVersionName, releaseAppVersionCode)

val generateRuntimeConfigs = tasks.register<GenerateRuntimeConfigsTask>("generateRuntimeConfigs") {
    outputDir.set(generatedRuntimeConfigDir)
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        localPropertiesFile.set(rootProject.layout.projectDirectory.file("local.properties"))
    }
    appVersionName.set(releaseAppVersionName)
    appVersionCode.set(releaseAppVersionCode)
}

val prepareWindowsRuntime = tasks.register<PrepareWindowsRuntimeTask>("prepareWindowsRuntime") {
    group = "distribution"
    description = "Copy bundled mediamp Windows runtime JARs into the desktop build."
    inputDir.set(layout.projectDirectory.dir(project.relativePath(windowsRuntimeInputDir)))
    inputDirPath.set(windowsRuntimeInputDir.absolutePath)
    outputDir.set(preparedWindowsRuntimeDir)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateRuntimeConfigs)
}

tasks.withType<AbstractJvmToolOperationTask>().configureEach {
    javaHome.set(java21Home)
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    executable = java21Launcher.get().executablePath.asFile.absolutePath
}

tasks.matching {
    it.name in setOf(
        "run",
        "createDistributable",
        "createReleaseDistributable",
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageExe",
        "packageReleaseExe",
    )
}.configureEach {
    dependsOn(prepareWindowsRuntime)
}

kotlin {
    jvmToolchain(21)

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    
    val iosTargets = listOf(
        iosArm64(),
        iosSimulatorArm64()
    )

    iosTargets.forEach { iosTarget ->
        iosTarget.compilations.getByName("main") {
            cinterops {
                create("commoncrypto") {
                    defFile(project.file("src/nativeInterop/cinterop/commoncrypto.def"))
                    compilerOpts("-I${project.projectDir}/src/nativeInterop/cinterop")
                }
            }

            if (iosDistribution == "full") {
                defaultSourceSet.kotlin.srcDir(fullCommonSourceDir)
            }
            defaultSourceSet.kotlin.srcDir(project.file(iosDistributionSourceDir))
            defaultSourceSet.dependencies {
                implementation(libs.ktor.client.darwin)
                if (iosDistribution == "full") {
                    implementation(libs.quickjs.kt)
                    implementation(libs.ksoup)
                }
            }
        }

        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=$iosFrameworkBundleId")
        }
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(generatedRuntimeConfigDir)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.java)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                // mediamp-mpv for Windows desktop player
                implementation("org.openani.mediamp:mediamp-api:0.1.0-dev-1")
                implementation("org.openani.mediamp:mediamp-mpv:0.1.0-dev-1")
                implementation(
                    fileTree(
                        mapOf(
                            "dir" to preparedWindowsRuntimeDir.get().asFile,
                            "include" to listOf("*.jar"),
                        ),
                    ),
                )
            }
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.splashscreen)
            implementation(libs.androidx.work.runtime)
            implementation(libs.coil.gif)
            implementation("androidx.recyclerview:recyclerview:1.4.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("com.google.code.gson:gson:2.11.0")
            implementation("io.github.peerless2012:ass-media:0.4.0-beta01")
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.androidx.media3.exoplayer.smoothstreaming)
            implementation(libs.androidx.media3.exoplayer.rtsp)
            implementation(libs.androidx.media3.datasource)
            implementation(libs.androidx.media3.datasource.okhttp)
            implementation(libs.androidx.media3.decoder)
            implementation(libs.androidx.media3.session)
            implementation(libs.androidx.media3.common)
            implementation(libs.androidx.media3.container)
            implementation(libs.androidx.media3.extractor)
            implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("lib-*.aar"))))
        }
        commonMain.dependencies {
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation("dev.chrisbanes.haze:haze:1.7.2")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.kermit)
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.functions)
            implementation(libs.reorderable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

afterEvaluate {
    dependencies {
        add("fullImplementation", libs.quickjs.kt)
        add("fullImplementation", libs.ksoup)
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "com.nuvio.app.DesktopAppKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("desktop-proguard-rules.pro"))
        }
        nativeDistributions {
            packageName = "Nuvio"
            packageVersion = windowsPackageVersion
            description = "Nuvio desktop"
            vendor = "Nuvio"
            modules("java.net.http")
            targetFormats(TargetFormat.Exe)
            windows {
                iconFile.set(project.file("desktop-icons/nuvio.ico"))
                menu = true
                menuGroup = "Nuvio"
                shortcut = true
                perUserInstall = true
                dirChooser = true
                console = false
                upgradeUuid = "d4b3ae5b-bf39-45fa-87be-4327ad0f7fd8"
                exePackageVersion = windowsPackageVersion
                msiPackageVersion = windowsPackageVersion
            }
        }
    }
}

configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-ui")
}

android {
    namespace = "com.nuvio.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    signingConfigs {
        create("release") {
            if (releaseKeystore != null && releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null) {
                storeFile = releaseKeystore
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.nuvio.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = releaseAppVersionCode
        versionName = releaseAppVersionName
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }
    sourceSets.getByName("full") {
        java.srcDir(fullCommonSourceDir)
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
