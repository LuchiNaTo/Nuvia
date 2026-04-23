/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv

import com.sun.jna.NativeLibrary
import com.sun.jna.WString
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

internal actual object LibraryLoader {
    private const val cacheDirPropertyName = "mediamp.cache.dir"
    private const val logFilePropertyName = "mediamp.log.file"
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    private val extractionLock = Any()
    private val loadLock = Any()

    @Volatile
    private var extractedDir: File? = null

    private val loadedRuntimeDirs = mutableSetOf<String>()

    actual fun loadLibraries(context: Any?) {
        val runtimeDir = ensureExtracted()
        ensureRuntimeLoaded(runtimeDir)
    }

    private fun ensureExtracted(): File {
        extractedDir?.let { return it }
        synchronized(extractionLock) {
            extractedDir?.let { return it }
            val dir = extractNativeBinaries()
            extractedDir = dir
            return dir
        }
    }

    private fun ensureRuntimeLoaded(runtimeDir: File) {
        val canonicalDir = runtimeDir.canonicalFile
        synchronized(loadLock) {
            val key = canonicalDir.absolutePath
            if (key in loadedRuntimeDirs) return

            val runtimeLibraries = runtimeLibrariesInLoadOrder(canonicalDir)
            configureWindowsDllSearchPath(canonicalDir)
            appendDiagnostic(
                "Loading mediamp runtime from ${canonicalDir.absolutePath} with libraries: " +
                    runtimeLibraries.joinToString(", ") { it.name },
            )

            try {
                if (osName.contains("win")) {
                    loadWindowsLibraries(runtimeLibraries)
                } else {
                    runtimeLibraries.forEach { library ->
                        System.load(library.absolutePath)
                    }
                }
            } catch (throwable: Throwable) {
                appendDiagnostic("Failed to load mediamp runtime from ${canonicalDir.absolutePath}", throwable)
                throw throwable
            }

            loadedRuntimeDirs += key
        }
    }

    private fun configureWindowsDllSearchPath(runtimeDir: File) {
    if (!osName.contains("win")) return

    runCatching {
        val kernel32 = NativeLibrary.getInstance("kernel32")
        val setDllDirectoryW = kernel32.getFunction("SetDllDirectoryW")
        val result = setDllDirectoryW.invokeInt(arrayOf(WString(runtimeDir.absolutePath)))

        appendDiagnostic(
            "Configured Windows DLL search path to ${runtimeDir.absolutePath}; success=${result != 0}",
        )

        check(result != 0) {
            "Failed to configure Windows DLL search path for ${runtimeDir.absolutePath}"
        }
    }.onFailure { throwable ->
        appendDiagnostic(
            "Failed to configure Windows DLL search path for ${runtimeDir.absolutePath}",
            throwable,
        )
        throw throwable
    }
}

    private fun extractNativeBinaries(): File {
        val dir = resolveExtractionDirectory()
        dir.mkdirs()

        val classLoader = LibraryLoader::class.java.classLoader
        val manifest = classLoader.getResourceAsStream("mpv-natives.txt")
            ?.bufferedReader()
            ?.readLines()
            ?: error(
                "mpv-natives.txt not found on classpath. " +
                    "Make sure mediamp-mpv-runtime-{os}-{arch} is on the classpath.",
            )

        manifest.forEach { fileName ->
            if (fileName.isBlank()) return@forEach
            val resource = classLoader.getResourceAsStream(fileName)
                ?: error("Native runtime file '$fileName' listed in mpv-natives.txt was not found on the classpath.")
            val target = dir.resolve(fileName)
            target.parentFile?.mkdirs()
            resource.use { input ->
                Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (!osName.contains("win")) {
                target.setReadable(true, false)
                target.setExecutable(true, false)
            }
        }

        appendDiagnostic(
            "Extracted mediamp runtime to ${dir.absolutePath}; files=" +
                dir.listFiles()
                    ?.sortedBy(File::getName)
                    ?.joinToString(", ") { it.name }
                    .orEmpty(),
        )

        return dir
    }

    private fun resolveExtractionDirectory(): File {
        val cacheRoot = System.getProperty(cacheDirPropertyName)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: defaultWindowsCacheRoot()

        if (cacheRoot != null) {
            return cacheRoot.resolve("mediamp-mpv").apply { mkdirs() }
        }

        return Files.createTempDirectory("mediamp-mpv").toFile().apply {
            deleteOnExit()
        }
    }

    private fun defaultWindowsCacheRoot(): File? {
        if (!osName.contains("win")) return null

        val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        val userHome = System.getProperty("user.home")?.takeIf { it.isNotBlank() }

        return when {
            localAppData != null -> File(localAppData)
            userHome != null -> File(userHome).resolve("AppData").resolve("Local")
            else -> null
        }?.resolve("Nuvio")?.resolve("cache")
    }

    private fun runtimeLibrariesInLoadOrder(runtimeDir: File): List<File> {
        val wrapper = runtimeDir.resolve(wrapperLibraryName())
        require(wrapper.isFile) {
            "MPV JNI wrapper not found at ${wrapper.absolutePath}. Ensure the runtime artifact was packaged correctly."
        }

        val allSharedLibraries = runtimeDir.listFiles()
            ?.filter { candidate ->
                candidate.isFile &&
                    candidate != wrapper &&
                    candidate.isSharedRuntimeLibrary(osName)
            }
            .orEmpty()

        val ffmpegPrefixes = listOf(
            "avutil-",
            "swresample-",
            "swscale-",
            "avcodec-",
            "avformat-",
            "avfilter-",
            "avdevice-",
        )
        val mpvPrefixes = listOf(
            "libmpv",
            "libass",
            "libplacebo",
        )

        val windowsPreludeLibraries = if (osName.contains("win")) {
            listOf(
                "libwinpthread-1.dll",
                "libgcc_s_seh-1.dll",
                "libstdc++-6.dll",
            ).mapNotNull { expectedName ->
                allSharedLibraries.firstOrNull { it.name.equals(expectedName, ignoreCase = true) }
            }
        } else {
            emptyList()
        }

        return buildList {
            addAll(windowsPreludeLibraries)

            if (osName.contains("win")) {
                allSharedLibraries
                    .filterNot { candidate ->
                        windowsPreludeLibraries.any { it.absolutePath == candidate.absolutePath } ||
                            ffmpegPrefixes.any(candidate.name::startsWith) ||
                            mpvPrefixes.any(candidate.name::startsWith)
                    }
                    .sortedByDescending(File::getName)
                    .let(::addAll)
            } else {
                allSharedLibraries
                    .filterNot { candidate ->
                        windowsPreludeLibraries.any { it.absolutePath == candidate.absolutePath } ||
                            ffmpegPrefixes.any(candidate.name::startsWith) ||
                            mpvPrefixes.any(candidate.name::startsWith)
                    }
                    .sortedBy(File::getName)
                    .let(::addAll)
            }

            ffmpegPrefixes.forEach { prefix ->
                allSharedLibraries
                    .filter { it.name.startsWith(prefix) }
                    .maxWithOrNull(compareBy<File>({ it.name.length }, { it.name }))
                    ?.let(::add)
            }

            mpvPrefixes.forEach { prefix ->
                allSharedLibraries
                    .filter { it.name.startsWith(prefix) }
                    .maxWithOrNull(compareBy<File>({ it.name.length }, { it.name }))
                    ?.let(::add)
            }

            allSharedLibraries
                .filter { candidate -> none { it.absolutePath == candidate.absolutePath } }
                .sortedBy(File::getName)
                .let(::addAll)

            add(wrapper)
        }
    }

    private fun loadWindowsLibraries(runtimeLibraries: List<File>) {
        val pending = runtimeLibraries.toMutableList()
        val failuresByLibrary = linkedMapOf<String, Throwable>()

        while (pending.isNotEmpty()) {
            var loadedAny = false
            val iterator = pending.iterator()

            while (iterator.hasNext()) {
                val library = iterator.next()
                try {
                    System.load(library.absolutePath)
                    iterator.remove()
                    failuresByLibrary.remove(library.name)
                    appendDiagnostic("Loaded Windows runtime library ${library.name}")
                    loadedAny = true
                } catch (throwable: Throwable) {
                    failuresByLibrary[library.name] = throwable
                }
            }

            if (loadedAny) continue

            val pendingSummary = pending.joinToString("; ") { library ->
                val failureMessage = failuresByLibrary[library.name]?.message ?: "unknown error"
                "${library.name}: $failureMessage"
            }
            val primaryFailure = failuresByLibrary[pending.first().name]
            throw IllegalStateException(
                "Failed to resolve Windows mediamp runtime dependency closure. Pending libraries: $pendingSummary",
                primaryFailure,
            )
        }
    }

    private fun wrapperLibraryName(): String =
        when {
            osName.contains("win") -> "mediampv.dll"
            osName.contains("mac") -> "libmediampv.dylib"
            else -> "libmediampv.so"
        }

    private fun appendDiagnostic(message: String, throwable: Throwable? = null) {
        val logFile = System.getProperty(logFilePropertyName)
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: return

        runCatching {
            logFile.parentFile?.mkdirs()
            val payload = buildString {
                append("[mediamp] ")
                append(message)
                append('\n')
                if (throwable != null) {
                    val traceWriter = StringWriter()
                    throwable.printStackTrace(PrintWriter(traceWriter))
                    append(traceWriter)
                    append('\n')
                }
            }
            Files.writeString(
                logFile.toPath(),
                payload,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}

private fun File.isSharedRuntimeLibrary(osName: String): Boolean =
    when {
        osName.contains("win") -> name.endsWith(".dll", ignoreCase = true)
        osName.contains("mac") -> name.endsWith(".dylib", ignoreCase = true)
        else -> name.endsWith(".so") || name.contains(".so.")
    }