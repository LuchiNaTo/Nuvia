package com.nuvio.app.desktop

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories

internal object DesktopPaths {
    private val userHome: Path by lazy {
        System.getProperty("user.home")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?.toAbsolutePath()
            ?.normalize()
            ?: Paths.get(".").toAbsolutePath().normalize()
    }

    private fun envBackedPath(
        envName: String,
        fallbackSegments: List<String>,
    ): Path =
        System.getenv(envName)
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?.toAbsolutePath()
            ?.normalize()
            ?: fallbackSegments.fold(userHome) { path, segment -> path.resolve(segment) }

    private fun ensureDirectory(path: Path): Path =
        path.apply {
            createDirectories()
        }

    val appDataRoot: Path by lazy {
        ensureDirectory(envBackedPath("APPDATA", listOf("AppData", "Roaming")).resolve("Nuvio"))
    }

    val localDataRoot: Path by lazy {
        ensureDirectory(envBackedPath("LOCALAPPDATA", listOf("AppData", "Local")).resolve("Nuvio"))
    }

    val preferencesRoot: Path by lazy {
        ensureDirectory(appDataRoot.resolve("preferences"))
    }

    val cacheRoot: Path by lazy {
        ensureDirectory(localDataRoot.resolve("cache"))
    }

    val logsRoot: Path by lazy {
        ensureDirectory(cacheRoot.resolve("logs"))
    }
}
