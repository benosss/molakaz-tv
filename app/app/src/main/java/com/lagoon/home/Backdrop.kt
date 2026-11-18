package com.lagoon.home

import android.graphics.BitmapFactory
import android.opengl.GLES20 as G
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** SurfaceViews in a transparent window need no hole: skip the full-screen CLEAR they draw every frame. */
class HoleFreeSurfaceView(ctx: android.content.Context) : android.view.SurfaceView(ctx) {
    override fun draw(canvas: android.graphics.Canvas) {}
    override fun dispatchDraw(canvas: android.graphics.Canvas) {}
}

class HoleFreeGLSurfaceView(ctx: android.content.Context) : GLSurfaceView(ctx) {
    override fun draw(canvas: android.graphics.Canvas) {}
    override fun dispatchDraw(canvas: android.graphics.Canvas) {}
}

/**
 * Translucent GL layer between the hardware video plane and the UI window.
 * The video itself never touches the GPU. This layer draws:
 *   1. the veil: the blurred video full screen, which hides the start, decoder restarts and the jump between films;
 *   2. the day/night dimming gradient (and a black cover when there are no keyframes to veil with);
 *   3. per glass panel: a soft SDF shadow, then pre-blurred keyframes of the video
 *      (2 per second, cross-faded in sync with playback) clipped to a rounded rect.
 * Output is premultiplied alpha so the hardware composer blends it over the video.
 * Mali-450 fragment shaders are fp16, so rounded-rect maths runs on panel-local coords.
 */
class Backdrop(private val view: GLSurfaceView) : GLSurfaceView.Renderer {
    @Volatile var dimBase = 0.10f
    @Volatile var dimTop = 0.30f
    @Volatile var dimBottom = 0.44f
    @Volatile var cover = 1f
    @Volatile var glassLift = 0.035f
    @Volatile var glassSat = 1.3f
    @Volatile var screenW = 1920f
    /** Off for the screensaver: Glass panels belong to Home. */
    var glassPanels = true
    @Volatile var posMs = 0L
    /** Keyframe i is k%04d.jpg (i + 1) in this folder, taken every [keyStepMs] of the film. */
    @Volatile var keyDir: File? = null
        set(v) { if (v != field) { field = v; keysGen++ } }
    @Volatile var keyCount = 0
    @Volatile private var keysGen = 0
    private var keysSeen = -1
    var keyStepMs = 500L
    /** Opacity of the full-screen blurred video (0 = off). */
    @Volatile var veil = 0f
    /** While a film change settles: 0 = the frozen picture of the old film, 1 = live keyframes. */
    @Volatile var swapMix = 1f
    @Volatile private var freezeFile: File? = null
    /** True once a frame with the veil (or video glass) has been drawn: Home holds its first draw until then. */
    @Volatile var drawnOnce = false
        private set

    /** Keep the picture at [pos] as the base of the blur, then let [swapMix] cross-fade to wherever playback goes. */
    fun freeze(pos: Long) { freezeFile = keyAt(pos); swapMix = 0f }

    /** The keyframe nearest [pos] in the current folder (taken before switching folders for the next film). */
    fun keyAt(pos: Long): File? { val n = keyCount; if (n < 1) return null
        return keyFile((((pos + keyStepMs / 2) / keyStepMs).toInt()).coerceIn(0, n - 1)) }

    private fun keyFile(i: Int) = keyDir?.let { File(it, "k%04d.jpg".format(i + 1)) }

    // ---- the next film, decoded by a second player into a GL texture and faded in over the video plane ----
    /** Opacity of the incoming film (0 = off). */
    @Volatile var incoming = 0f
    /** Surface the second player decodes into; null until the GL layer is up. */
    @Volatile var incomingSurface: android.view.Surface? = null
        private set
    var onIncomingSurface: ((android.view.Surface?) -> Unit)? = null
    /** Counts frames handed to the texture (the carousel watches it to know the next film has started). */
    @Volatile var incomingFrames = 0L
        private set
    private var oesTex = 0
    private var st: android.graphics.SurfaceTexture? = null
    @Volatile private var stFresh = false
    private val stM = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    private var pOes = 0

    private var W = 1; private var H = 1
    private var pDim = 0; private var pGlass = 0; private var pShadow = 0; private var pVeil = 0
    private val tex = IntArray(3)   // two live keyframes (by parity) + the frozen one
    private val loaded = intArrayOf(-1, -1)
    private var frozenOk = false
    private var texW = 480f; private var texH = 270f
    private val full: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(24 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val qv = FloatArray(24)
    private val loc = HashMap<String, Int>()
    private fun u(p: Int, n: String) = loc.getOrPut("$p/$n") { G.glGetUniformLocation(p, n) }
    private fun at(p: Int, n: String) = loc.getOrPut("$p@$n") { G.glGetAttribLocation(p, n) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        loc.clear()
        pDim = program(VS_DIM, FS_DIM)
        pGlass = program(VS_GLASS, FS_GLASS)
        pShadow = program(VS_PANEL, FS_SHADOW)
        pVeil = program(VS_VEIL, FS_VEIL)
        pOes = program(VS_OES, FS_OES)
        val o = IntArray(1); G.glGenTextures(1, o, 0); oesTex = o[0]
        G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_MIN_FILTER, G.GL_LINEAR)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_MAG_FILTER, G.GL_LINEAR)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_WRAP_S, G.GL_CLAMP_TO_EDGE)
        G.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, G.GL_TEXTURE_WRAP_T, G.GL_CLAMP_TO_EDGE)
        st?.release(); incomingSurface?.release()
        val t = android.graphics.SurfaceTexture(oesTex).apply { setOnFrameAvailableListener { stFresh = true; incomingFrames++; view.requestRender() } }
        st = t
        val sf = android.view.Surface(t); incomingSurface = sf
        onIncomingSurface?.invoke(sf)
        G.glGenTextures(3, tex, 0)
        for (t in tex) {
            G.glBindTexture(G.GL_TEXTURE_2D, t)
            G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_MIN_FILTER, G.GL_LINEAR)
            G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_MAG_FILTER, G.GL_LINEAR)
            G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_S, G.GL_CLAMP_TO_EDGE)
            G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_T, G.GL_CLAMP_TO_EDGE)
        }
        loaded[0] = -1; loaded[1] = -1; frozenOk = false
    }

    /** The GL context is going away (screensaver closing): drop the decode surface. */
    fun releaseIncoming() { onIncomingSurface?.invoke(null); incomingSurface?.release(); incomingSurface = null; st?.release(); st = null }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { W = w; H = h }

    /** Keyframe i lives in texture slot i % 2, so the current and next frame never collide. */
    private fun ensure(i: Int): Boolean {
        val slot = i and 1
        if (loaded[slot] == i) return true
        val b = BitmapFactory.decodeFile(keyFile(i)?.path ?: return false) ?: return false
        texW = b.width.toFloat(); texH = b.height.toFloat()
        G.glBindTexture(G.GL_TEXTURE_2D, tex[slot]); GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, b, 0); b.recycle()
        loaded[slot] = i
        return true
    }

    private fun loadFrozen(f: File) {
        val b = BitmapFactory.decodeFile(f.path) ?: return
        G.glBindTexture(G.GL_TEXTURE_2D, tex[2]); GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, b, 0); b.recycle()
        frozenOk = true
    }

    private var frames = 0
    override fun onDrawFrame(gl: GL10?) {
        if (frames++ == 0) BootTrace.mark("glass ready")
        G.glViewport(0, 0, W, H)
        G.glClearColor(0f, 0f, 0f, 0f); G.glClear(G.GL_COLOR_BUFFER_BIT)
        G.glEnable(G.GL_BLEND); G.glBlendFunc(G.GL_ONE, G.GL_ONE_MINUS_SRC_ALPHA)

        // Read the position before the freeze request: freeze() is always set before a jump, so a new position
        // here means the request is visible too, and the old picture never flashes out for a frame.
        val pos = posMs
        val fz = freezeFile
        if (keysGen != keysSeen) { keysSeen = keysGen; loaded[0] = -1; loaded[1] = -1 }
        val n = keyCount
        var texA = 0; var texB = 0; var mixF = 0f; var haveKeys = false
        if (n >= 2) {
            val total = n * keyStepMs
            val p = ((pos % total) + total) % total
            val i0 = (p / keyStepMs).toInt(); val i1 = (i0 + 1) % n
            if (fz != null) { loadFrozen(fz); freezeFile = null }
            val ok0 = ensure(i0); val ok1 = ensure(i1)
            haveKeys = ok0 || loaded[0] >= 0 || loaded[1] >= 0
            val sm = swapMix
            if (sm < 0.999f && frozenOk) { texA = tex[2]; texB = tex[i0 and 1]; mixF = sm }
            else { texA = tex[i0 and 1]; texB = tex[if (ok1) i1 and 1 else i0 and 1]; mixF = (p % keyStepMs).toFloat() / keyStepMs }
        }

        val v = veil
        if (v > 0.003f && haveKeys) {
            G.glUseProgram(pVeil)
            val aPos = at(pVeil, "aPos")
            full.position(0); G.glVertexAttribPointer(aPos, 2, G.GL_FLOAT, false, 8, full); G.glEnableVertexAttribArray(aPos)
            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(G.GL_TEXTURE_2D, texA)
            G.glActiveTexture(G.GL_TEXTURE1); G.glBindTexture(G.GL_TEXTURE_2D, texB)
            G.glUniform1i(u(pVeil, "uA"), 0); G.glUniform1i(u(pVeil, "uB"), 1)
            G.glUniform1f(u(pVeil, "uF"), mixF); G.glUniform1f(u(pVeil, "uAlpha"), v.coerceAtMost(1f))
            G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
            G.glActiveTexture(G.GL_TEXTURE0)
        }

        val inc = incoming
        if (stFresh) { stFresh = false; runCatching { st?.updateTexImage(); st?.getTransformMatrix(stM) } }
        if (inc > 0.003f && st != null) {
            G.glUseProgram(pOes)
            val aP = at(pOes, "aPos")
            full.position(0); G.glVertexAttribPointer(aP, 2, G.GL_FLOAT, false, 8, full); G.glEnableVertexAttribArray(aP)
            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
            G.glUniform1i(u(pOes, "uTex"), 0)
            G.glUniformMatrix4fv(u(pOes, "uM"), 1, false, stM, 0)
            G.glUniform1f(u(pOes, "uAlpha"), inc.coerceAtMost(1f))
            G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
        }

        G.glUseProgram(pDim)
        val aPos = at(pDim, "aPos")
        full.position(0); G.glVertexAttribPointer(aPos, 2, G.GL_FLOAT, false, 8, full); G.glEnableVertexAttribArray(aPos)
        G.glUniform3f(u(pDim, "uDim"), dimBase, dimTop, dimBottom)
        G.glUniform1f(u(pDim, "uCover"), cover)
        G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
        if (v > 0.5f && haveKeys || cover < 0.5f) drawnOnce = true

        val panels = if (glassPanels) Glass.snapshot else FloatArray(0)
        if (panels.isEmpty() || !haveKeys || cover >= 0.999f) { G.glDisable(G.GL_BLEND); return }
        val k = W / screenW
        var i = 0
        while (i + Glass.STRIDE <= panels.size) {
            val x = panels[i] * k; val y = panels[i + 1] * k; val w = panels[i + 2] * k; val h = panels[i + 3] * k
            val r = panels[i + 4] * k; val a = panels[i + 5] * (1f - cover); val shadow = panels[i + 6] * (1f - cover)
            if (shadow > 0.01f) {
                panelQuad(x, y, w, h, D.u(36f) * k)
                G.glUseProgram(pShadow); bindPanelAttribs(pShadow)
                G.glUniform2f(u(pShadow, "uHalf"), w / 2f, h / 2f)
                G.glUniform1f(u(pShadow, "uR"), r)
                G.glUniform1f(u(pShadow, "uA"), shadow)
                G.glUniform1f(u(pShadow, "uBlur"), D.u(22f) * k)
                G.glUniform1f(u(pShadow, "uOff"), D.u(8f) * k)
                G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
            }
            panelQuad(x, y, w, h, 2f)
            G.glUseProgram(pGlass); bindPanelAttribs(pGlass)
            G.glActiveTexture(G.GL_TEXTURE0); G.glBindTexture(G.GL_TEXTURE_2D, texA)
            G.glActiveTexture(G.GL_TEXTURE1); G.glBindTexture(G.GL_TEXTURE_2D, texB)
            G.glUniform1i(u(pGlass, "uA"), 0)
            G.glUniform1i(u(pGlass, "uB"), 1)
            G.glUniform1f(u(pGlass, "uF"), mixF)
            // Four bilinear taps ~1.2 texels apart: a soft 4x4 tent that melts any remaining block edges.
            G.glUniform2f(u(pGlass, "uTexel"), 1.2f / texW, 1.2f / texH)
            G.glUniform2f(u(pGlass, "uHalf"), w / 2f, h / 2f)
            G.glUniform1f(u(pGlass, "uR"), r)
            G.glUniform1f(u(pGlass, "uAlpha"), a)
            G.glUniform1f(u(pGlass, "uLift"), glassLift)
            G.glUniform1f(u(pGlass, "uSat"), glassSat)
            G.glDrawArrays(G.GL_TRIANGLE_STRIP, 0, 4)
            i += Glass.STRIDE
        }
        G.glActiveTexture(G.GL_TEXTURE0)
        G.glDisable(G.GL_BLEND)
    }

    /** Quad around a panel (surface px, y down): NDC position, panel-local px, image UV (top-left origin). */
    private fun panelQuad(x: Float, y: Float, w: Float, h: Float, pad: Float) {
        val cx = x + w / 2f; val cy = y + h / 2f
        val x0 = x - pad; val x1 = x + w + pad; val y0 = y - pad; val y1 = y + h + pad
        var k = 0
        for (yy in floatArrayOf(y0, y1)) for (xx in floatArrayOf(x0, x1)) {
            qv[k++] = xx / W * 2f - 1f; qv[k++] = 1f - yy / H * 2f
            qv[k++] = xx - cx; qv[k++] = yy - cy
            qv[k++] = xx / W; qv[k++] = yy / H
        }
        quad.position(0); quad.put(qv); quad.position(0)
    }

    private fun bindPanelAttribs(p: Int) {
        val aPos = at(p, "aPos"); val aLocal = at(p, "aLocal"); val aUV = at(p, "aUV")
        quad.position(0); G.glVertexAttribPointer(aPos, 2, G.GL_FLOAT, false, 24, quad); G.glEnableVertexAttribArray(aPos)
        quad.position(2); G.glVertexAttribPointer(aLocal, 2, G.GL_FLOAT, false, 24, quad); G.glEnableVertexAttribArray(aLocal)
        if (aUV >= 0) { quad.position(4); G.glVertexAttribPointer(aUV, 2, G.GL_FLOAT, false, 24, quad); G.glEnableVertexAttribArray(aUV) }
    }

    companion object {
        private const val TAG = "Backdrop"
        private fun floats(vararg f: Float): FloatBuffer =
            ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(f); position(0) }

        private fun shader(type: Int, src: String): Int {
            val s = G.glCreateShader(type); G.glShaderSource(s, src); G.glCompileShader(s)
            val ok = IntArray(1); G.glGetShaderiv(s, G.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: " + G.glGetShaderInfoLog(s))
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = G.glCreateProgram()
            G.glAttachShader(p, shader(G.GL_VERTEX_SHADER, vs)); G.glAttachShader(p, shader(G.GL_FRAGMENT_SHADER, fs))
            G.glLinkProgram(p)
            val ok = IntArray(1); G.glGetProgramiv(p, G.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "link: " + G.glGetProgramInfoLog(p))
            return p
        }

        private const val VS_DIM = """
attribute vec2 aPos; varying float vY;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vY = 0.5 - aPos.y * 0.5; }"""

        private const val FS_DIM = """
precision mediump float; uniform vec3 uDim; uniform float uCover; varying float vY;
void main(){
  float d = uDim.x + uDim.y * (1.0 - smoothstep(0.0, 0.38, vY)) + uDim.z * smoothstep(0.52, 1.0, vY);
  float a = clamp(d, 0.0, 0.95);
  a = a + (1.0 - a) * uCover;
  gl_FragColor = vec4(0.0, 0.0, 0.0, a);
}"""

        private const val VS_VEIL = """
attribute vec2 aPos; varying vec2 vUV;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUV = vec2(0.5 + aPos.x * 0.5, 0.5 - aPos.y * 0.5); }"""

        /** One bilinear tap per keyframe is enough here: the frames are already heavily blurred. */
        private const val FS_VEIL = """
precision mediump float; uniform sampler2D uA; uniform sampler2D uB; uniform float uF; uniform float uAlpha; varying vec2 vUV;
void main(){ vec3 c = mix(texture2D(uA, vUV).rgb, texture2D(uB, vUV).rgb, uF); gl_FragColor = vec4(c * uAlpha, uAlpha); }"""

        private const val VS_OES = """
attribute vec2 aPos; uniform mat4 uM; varying vec2 vUV;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vUV = (uM * vec4(0.5 + aPos.x * 0.5, 0.5 + aPos.y * 0.5, 0.0, 1.0)).xy; }"""

        private const val FS_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float; uniform samplerExternalOES uTex; uniform float uAlpha; varying vec2 vUV;
void main(){ gl_FragColor = vec4(texture2D(uTex, vUV).rgb * uAlpha, uAlpha); }"""

        private const val VS_PANEL = """
attribute vec2 aPos; attribute vec2 aLocal; attribute vec2 aUV; varying vec2 vLocal; varying vec2 vUV;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vLocal = aLocal; vUV = aUV; }"""

        /** Glass: the four sample positions are worked out per vertex (Mali-450 is slow at dependent texture reads). */
        private const val VS_GLASS = """
attribute vec2 aPos; attribute vec2 aLocal; attribute vec2 aUV; uniform vec2 uTexel;
varying vec2 vLocal; varying vec2 vUV0; varying vec2 vUV1; varying vec2 vUV2; varying vec2 vUV3;
void main(){ gl_Position = vec4(aPos, 0.0, 1.0); vLocal = aLocal;
  vUV0 = aUV + vec2(-uTexel.x, -uTexel.y); vUV1 = aUV + vec2(uTexel.x, -uTexel.y);
  vUV2 = aUV + vec2(-uTexel.x, uTexel.y); vUV3 = aUV + vec2(uTexel.x, uTexel.y); }"""

        private const val SD_BOX = """
float sdBox(vec2 p, vec2 b, float r){ vec2 q = abs(p) - b + vec2(r); return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }"""

        private const val FS_GLASS = """
precision mediump float; uniform sampler2D uA; uniform sampler2D uB; uniform float uF;
uniform vec2 uHalf; uniform float uR; uniform float uAlpha; uniform float uLift; uniform float uSat;
varying vec2 vLocal; varying vec2 vUV0; varying vec2 vUV1; varying vec2 vUV2; varying vec2 vUV3;""" + SD_BOX + """
void main(){
  float m = (1.0 - smoothstep(-0.9, 0.9, sdBox(vLocal, uHalf, uR))) * uAlpha;
  vec3 a = texture2D(uA, vUV0).rgb + texture2D(uA, vUV1).rgb + texture2D(uA, vUV2).rgb + texture2D(uA, vUV3).rgb;
  vec3 b = texture2D(uB, vUV0).rgb + texture2D(uB, vUV1).rgb + texture2D(uB, vUV2).rgb + texture2D(uB, vUV3).rgb;
  vec3 c = mix(a, b, uF) * 0.25;
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = clamp(mix(vec3(l), c, uSat) + uLift, 0.0, 1.0);
  gl_FragColor = vec4(c * m, m);
}"""

        private const val FS_SHADOW = """
precision mediump float; uniform vec2 uHalf; uniform float uR; uniform float uA; uniform float uBlur; uniform float uOff;
varying vec2 vLocal; varying vec2 vUV;""" + SD_BOX + """
void main(){
  float d = sdBox(vLocal - vec2(0.0, uOff), uHalf, uR);
  gl_FragColor = vec4(0.0, 0.0, 0.0, uA * (1.0 - smoothstep(-uBlur * 0.4, uBlur, d)));
}"""
    }
}
