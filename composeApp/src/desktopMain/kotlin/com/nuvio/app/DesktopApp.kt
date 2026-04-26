package com.nuvio.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nuvio.app.desktop.DesktopRuntimeDiagnostics
import com.nuvio.app.desktop.DesktopPaths
import java.awt.Color as AwtColor

private val DesktopWindowBackground = AwtColor(0x0D, 0x0D, 0x0D)

private fun configureMacOsNativeAppearance() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("mac")) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

@Volatile
private var composeInteropBlendingSource: String = "non-windows-unchanged"

private fun configureWindowsComposeInterop() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("windows")) return

    val explicitJvm = System.getProperty("compose.interop.blending")
    val nuvioOverride = System.getProperty("nuvio.compose.interop.blending")
        ?: System.getenv("NUVIO_COMPOSE_INTEROP_BLENDING")

    composeInteropBlendingSource = when {
        !explicitJvm.isNullOrBlank() -> "jvm-property"
        !nuvioOverride.isNullOrBlank() -> {
            System.setProperty("compose.interop.blending", nuvioOverride)
            "nuvio-override"
        }
        else -> "default-windows-unset"
    }
}

private fun configureDesktopRuntimeEnvironment() {
    System.setProperty("sun.awt.noerasebackground", "true")
    configureWindowsComposeInterop()
    DesktopRuntimeDiagnostics.initialize()
    System.setProperty("mediamp.cache.dir", DesktopPaths.cacheRoot.toString())
    DesktopRuntimeDiagnostics.info(
        tag = "DesktopApp",
        message = "Desktop cache root=${DesktopPaths.cacheRoot}, logFile=${DesktopRuntimeDiagnostics.logFile}",
    )
    DesktopRuntimeDiagnostics.info(
        tag = "DesktopApp",
        message = "compose.interop.blending effective=${System.getProperty("compose.interop.blending") ?: "<unset>"} " +
            "source=$composeInteropBlendingSource, " +
            "requestedSkikoRenderApi=${System.getProperty("skiko.renderApi") ?: "<default>"}",
    )
    DesktopRuntimeDiagnostics.logStartupConfiguration()
}

fun main() {
    configureMacOsNativeAppearance()
    configureDesktopRuntimeEnvironment()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Nuvio",
        ) {
            DisposableEffect(window) {
                window.background = DesktopWindowBackground
                window.contentPane.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                onDispose { }
            }

            CompositionLocalProvider(LocalDesktopWindow provides window) {
                App()
            }
        }
    }
}
