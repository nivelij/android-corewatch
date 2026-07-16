package com.corewatch.monitor

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/** GPU identity queried from OpenGL ES (the only vendor-neutral way to name the GPU without root). */
data class GpuInfo(
    val renderer: String?,   // e.g. "Adreno (TM) 740"
    val vendor: String?,     // e.g. "Qualcomm"
    val glVersion: String?,  // e.g. "OpenGL ES 3.2 v1.r45p1"
) {
    companion object {
        val EMPTY = GpuInfo(null, null, null)

        /**
         * Spins up a 1×1 off-screen EGL/GLES2 context, reads the GL strings, and tears it down.
         * Cheap and self-contained; returns [EMPTY] if any step fails (e.g. no EGL on the device).
         */
        fun read(): GpuInfo {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return EMPTY
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return EMPTY

            var context = EGL14.EGL_NO_CONTEXT
            var surface = EGL14.EGL_NO_SURFACE
            try {
                val configAttrs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfig = IntArray(1)
                if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfig, 0) ||
                    numConfig[0] == 0 || configs[0] == null
                ) {
                    return EMPTY
                }

                val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
                if (context == EGL14.EGL_NO_CONTEXT) return EMPTY

                val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttrs, 0)
                if (surface == EGL14.EGL_NO_SURFACE) return EMPTY

                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return EMPTY

                return GpuInfo(
                    renderer = GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.ifBlank { null },
                    vendor = GLES20.glGetString(GLES20.GL_VENDOR)?.trim()?.ifBlank { null },
                    glVersion = GLES20.glGetString(GLES20.GL_VERSION)?.trim()?.ifBlank { null },
                )
            } catch (_: Exception) {
                return EMPTY
            } finally {
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                EGL14.eglTerminate(display)
            }
        }
    }
}
