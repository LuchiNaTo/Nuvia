package com.nuvio.app.desktop

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.nuvio.app.core.build.RuntimeConfigInfo
import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.features.settings.CommunityConfig
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object DesktopRuntimeDiagnostics {
    private const val logFilePropertyName = "nuvio.desktop.logFile"
    private const val mediampLogFilePropertyName = "mediamp.log.file"
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
    private val writeLock = Any()

    private val logWriter = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            write(severity.name, tag, message, throwable)
        }
    }

    private var initialized = false

    val logFile: Path by lazy {
        DesktopPaths.logsRoot.resolve("desktop-runtime.log")
    }

    fun initialize() {
        synchronized(writeLock) {
            if (initialized) return

            Files.createDirectories(logFile.parent)
            Files.writeString(
                logFile,
                "\n=== Nuvio desktop session ${timestamp()} ===\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )

            System.setProperty(logFilePropertyName, logFile.toString())
            System.setProperty(mediampLogFilePropertyName, logFile.toString())

            Logger.setMinSeverity(Severity.Verbose)
            Logger.addLogWriter(logWriter)

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                error(
                    tag = "UncaughtException",
                    message = "Unhandled exception on thread '${thread.name}'",
                    throwable = throwable,
                )
            }

            initialized = true
        }
    }

    fun info(tag: String, message: String) = write("INFO", tag, message, null)

    fun warn(tag: String, message: String, throwable: Throwable? = null) =
        write("WARN", tag, message, throwable)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        write("ERROR", tag, message, throwable)

    fun logStartupConfiguration() {
        val supabaseBaseUrl = SupabaseConfig.URL.trim().removeSuffix("/")
        val authUrl = supabaseBaseUrl.takeIf(String::isNotBlank)?.let { "$it/auth/v1" } ?: "<blank>"
        val restUrl = supabaseBaseUrl.takeIf(String::isNotBlank)?.let { "$it/rest/v1" } ?: "<blank>"
        val avatarBaseUrl = supabaseBaseUrl.takeIf(String::isNotBlank)
            ?.let { "$it/storage/v1/object/public/avatars" }
            ?: "<blank>"
        val tmdbSettings = TmdbSettingsRepository.snapshot()

        info(
            tag = "RuntimeConfig",
            message = buildString {
                append("mode=")
                append(RuntimeConfigInfo.MODE)
                append(", supabaseSource=")
                append(RuntimeConfigInfo.SUPABASE_SOURCE)
                append(", communitySource=")
                append(RuntimeConfigInfo.COMMUNITY_SOURCE)
                append(", introDbSource=")
                append(RuntimeConfigInfo.INTRODB_SOURCE)
                append(", traktSource=")
                append(RuntimeConfigInfo.TRAKT_SOURCE)
                append(", authUrl=")
                append(authUrl)
                append(", restUrl=")
                append(restUrl)
                append(", avatarBaseUrl=")
                append(avatarBaseUrl)
                append(", donationsBaseUrl=")
                append(CommunityConfig.DONATIONS_BASE_URL.ifBlank { "<blank>" })
                append(", tmdbImageBaseUrl=https://image.tmdb.org/t/p/")
                append(", tmdbEnabled=")
                append(tmdbSettings.enabled)
                append(", tmdbHasApiKey=")
                append(tmdbSettings.hasApiKey)
                append(", tmdbLanguage=")
                append(tmdbSettings.language)
            },
        )
    }

    private fun write(
        severity: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        synchronized(writeLock) {
            val builder = StringBuilder()
                .append(timestamp())
                .append(" [")
                .append(severity)
                .append("] [")
                .append(tag)
                .append("] ")
                .append(message)
                .append('\n')

            if (throwable != null) {
                val traceWriter = StringWriter()
                throwable.printStackTrace(PrintWriter(traceWriter))
                builder.append(traceWriter).append('\n')
            }

            Files.writeString(
                logFile,
                builder.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun timestamp(): String =
        timestampFormatter.format(ZonedDateTime.now())
}
