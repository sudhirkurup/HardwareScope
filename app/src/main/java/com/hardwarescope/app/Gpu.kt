package com.hardwarescope.app

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads GPU identity through an off-screen OpenGL ES context and runs a small shader benchmark. */
object Gpu {
    data class Info(
        val renderer: String,
        val vendor: String,
        val version: String,
        val glsl: String,
        val extensions: Int,
        val maxTexture: Int
    )

    @Volatile
    private var cached: Info? = null

    fun info(): Info? {
        cached?.let { return it }
        val r = withContext(16, 16) {
            val ext = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val max = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
            Info(
                GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown",
                GLES20.glGetString(GLES20.GL_VENDOR) ?: "Unknown",
                GLES20.glGetString(GLES20.GL_VERSION) ?: "Unknown",
                GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "Unknown",
                ext.split(" ").count { it.isNotBlank() },
                max[0]
            )
        }
        cached = r
        return r
    }

    /** Creates a throw-away pbuffer context on the calling thread. EGL display is intentionally not terminated (the UI shares it). */
    private fun <T> withContext(w: Int, h: Int, block: () -> T): T? {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (dpy == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return null
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null
        val cfg = configs[0] ?: return null
        val ctx = EGL14.eglCreateContext(
            dpy, cfg, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (ctx == EGL14.EGL_NO_CONTEXT) return null
        val surf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        if (surf == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(dpy, ctx); return null
        }
        return try {
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) null else block()
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
        }
    }

    private const val VS = "attribute vec2 p; varying vec2 uv; void main(){ uv = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }"
    private const val FS = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        varying vec2 uv; uniform float t;
        void main(){
          vec2 q = uv; float v = 0.0;
          for (int i = 0; i < 24; i++) {
            q = vec2(sin(q.x * 3.1 + t) + cos(q.y * 2.7), cos(q.x * 1.9) - sin(q.y * 3.3 + t));
            v += length(q);
          }
          gl_FragColor = vec4(fract(v), fract(v * 0.5), fract(v * 0.25), 1.0);
        }"""

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Shader error: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    /** Renders a math-heavy full-screen shader into a 1024×1024 off-screen buffer for ~3 s. */
    fun benchmark(): String {
        val size = 1024
        val res = withContext(size, size) {
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, VS))
            GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, FS))
            GLES20.glLinkProgram(prog)
            GLES20.glUseProgram(prog)
            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(quad)
            (buf as java.nio.Buffer).position(0) // cast avoids covariant-return issues on old runtimes
            val loc = GLES20.glGetAttribLocation(prog, "p")
            val tLoc = GLES20.glGetUniformLocation(prog, "t")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glViewport(0, 0, size, size)
            val px = ByteBuffer.allocateDirect(4)
            // warm-up
            for (i in 0 until 5) {
                GLES20.glUniform1f(tLoc, i.toFloat()); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES20.glFinish()
            var frames = 0
            val t0 = System.nanoTime()
            while (System.nanoTime() - t0 < 3_000_000_000L && frames < 2000) {
                GLES20.glUniform1f(tLoc, frames * 0.01f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                frames++
                if (frames % 10 == 0) GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            }
            GLES20.glFinish()
            val secs = (System.nanoTime() - t0) / 1e9
            GLES20.glDeleteProgram(prog)
            val fps = frames / secs
            f("%.1f frames/s · %.0f Mpixel/s (1024×1024 shader)", fps, fps * size * size / 1e6)
        }
        return res ?: "OpenGL ES 2.0 context unavailable"
    }
}
