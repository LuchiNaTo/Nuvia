/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU GENERAL PUBLIC LICENSE version 3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.vlc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.vlc.SkiaBitmapVideoSurface.Companion.ALLOWED_DRAW_FRAMES
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

@InternalMediampApi
public class SkiaBitmapVideoSurface {
    private val composeBitmap = mutableStateOf<ImageBitmap?>(null)

    public val enableRendering: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Set this to non-zero to draw frames even if [enableRendering] is false.
     *
     * @see ALLOWED_DRAW_FRAMES
     */
    @JvmField
    @Volatile
    public var allowedDrawFrames: Int = 0

    private val displayCount = AtomicInteger(0)
    private var currentImage: BufferedImage? = null
    private var currentRenderCallback: OfficialRenderCallback? = null

    @Volatile
    private var pitchBytes: Int = 0

    @Volatile
    private var lineCount: Int = 0

    @Volatile
    private var formatWidth: Int = 0

    @Volatile
    private var formatHeight: Int = 0

    public fun setAllowedDrawFrames(value: Int) {
        ALLOWED_DRAW_FRAMES.set(this, value)
    }

    public val bitmap: ImageBitmap? by composeBitmap

    public fun clearBitmap() {
        VlcRuntimeDiagnostics.info(
            tag = "SkiaBitmapVideoSurface",
            message = "clearBitmap",
        )
        composeBitmap.value = null
    }

    public fun createVideoSurface(mediaPlayerFactory: MediaPlayerFactory): VideoSurface {
        val renderCallback = OfficialRenderCallback()
        currentRenderCallback = renderCallback
        val bufferFormatCallback = OfficialBufferFormatCallback(renderCallback)
        val surface = mediaPlayerFactory.videoSurfaces().newVideoSurface(
            bufferFormatCallback,
            renderCallback,
            true,
        )
        VlcRuntimeDiagnostics.info(
            tag = "SkiaBitmapVideoSurface",
            message = "VLC_VIDEO_SURFACE_MODE=CALLBACK_BITMAP_FALLBACK surfaceType=${surface::class.qualifiedName}",
        )
        return surface
    }

    private fun newVideoBuffer(width: Int, height: Int, pitch: Int, lines: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        currentImage = image
        formatWidth = width
        formatHeight = height
        pitchBytes = pitch
        lineCount = lines
        currentRenderCallback?.setImageBuffer(image)
    }

    private inner class OfficialBufferFormatCallback(
        private val renderCallback: OfficialRenderCallback,
    ) : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            return try {
                val bufferFormat = RV32BufferFormat(sourceWidth, sourceHeight)
                val pitch = bufferFormat.pitches.firstOrNull() ?: (bufferFormat.width * 4)
                val lines = bufferFormat.lines.firstOrNull() ?: bufferFormat.height
                newVideoBuffer(bufferFormat.width, bufferFormat.height, pitch, lines)
                bufferFormat
            } catch (t: Throwable) {
                VlcRuntimeDiagnostics.error(
                    tag = "SkiaBitmapVideoSurface",
                    message = "getBufferFormat failed width=$sourceWidth height=$sourceHeight",
                    throwable = t,
                )
                throw t
            }
        }

        override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
            super.allocatedBuffers(buffers)
        }
    }

    private inner class OfficialRenderCallback : RenderCallbackAdapter() {
        fun setImageBuffer(image: BufferedImage) {
            setBuffer((image.raster.dataBuffer as DataBufferInt).data)
        }

        override fun onDisplay(mediaPlayer: MediaPlayer, buffer: IntArray) {
            val frameIndex = displayCount.incrementAndGet()
            val allowedDrawFramesValue = ALLOWED_DRAW_FRAMES.get(this@SkiaBitmapVideoSurface)

            if (!enableRendering.value) {
                if (allowedDrawFramesValue <= 0) {
                    return
                }
                if (ALLOWED_DRAW_FRAMES.decrementAndGet(this@SkiaBitmapVideoSurface) < 0) {
                    return
                }
            }

            val image = currentImage
            if (image == null) {
                return
            }

            composeBitmap.value = image.toComposeImageBitmap()
        }
    }

    private companion object {
        private val ALLOWED_DRAW_FRAMES = AtomicIntegerFieldUpdater.newUpdater(
            SkiaBitmapVideoSurface::class.java,
            "allowedDrawFrames",
        )
    }
}
