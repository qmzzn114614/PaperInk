package com.dsh.paperink.render.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 3D 卷曲翻页：只负责「把一张页面位图按圆筒弯曲画出来」，不碰 PDF、不碰手写、不碰手势。
 *
 * 为什么单独一个 GLSurfaceView 而不是把整页渲染都搬进 GL：
 * 平时的阅读、缩放、写字仍然是 Canvas 那两条 View，稳定且已验证；
 * 这个 View 只在「手指从右边缘拖」的那一小段时间可见，画完立刻隐藏。
 * 这样即使卷曲出 bug，也绝不会影响看 PDF 和写字。
 *
 * 形变模型（顶点着色器里算）：折线 fold = 1 - progress 从左往右收缩，
 * 折线右侧的部分绕一根竖直圆柱卷起来：x' = fold + R·sinθ，z = R·(1-cosθ)，θ = (x-fold)/R。
 * 法线取 (-sinθ, 0, cosθ)，直接可以拿来做漫反射与高光；θ>90° 时按纸背色渲染。
 */
class PageCurlView(context: Context) : GLSurfaceView(context) {

    private val renderer = CurlRenderer()

    /** 动画走到 1（纸翻过去了）：调用方负责真正翻页。 */
    var onTurned: (() -> Unit)? = null

    /** 回弹结束（纸回到原位）。 */
    var onCancelled: (() -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        // 必须置顶 + TRANSLUCENT：置顶时 GL 表面才会和下面窗口内容做 alpha 混合，
        // 否则透明区域显示成一块不透明的黑，把整页 PDF 盖住。
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        // GL 线程不能直接碰 View：回调和「再画一帧」都从这里转回主线程。
        renderer.postToMain = { r -> post(r) }
        renderer.requestRenderCallback = { requestRender() }
        setRenderer(renderer)
        // 平时按需渲染：没在翻页时一帧都不画，省电也省 120Hz 的合成开销。
        renderMode = RENDERMODE_WHEN_DIRTY
        visibility = INVISIBLE
        isClickable = false
        isFocusable = false
    }

    /** 永远不吃触摸：事件要落到底下的手写层和分块层上。 */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false

    /**
     * 开始一次翻页。
     *
     * @param page 当前页面（PDF + 手写）合成后的位图
     * @param rect 这张纸在屏幕上的矩形（像素），用来把页面摆到正确位置
     * @param backward true = 向后翻（纸从左边卷向右）
     */
    fun begin(
        page: Bitmap,
        rect: RectF,
        viewWidth: Int,
        viewHeight: Int,
        backward: Boolean = false,
    ) {
        busy = true
        // 向后翻把几何左右镜像：同一套卷曲数学，纸从左边卷起来。
        renderer.mirror = if (backward) 1f else 0f
        renderer.pending = Frame(page, rect, viewWidth, viewHeight, renderer.pending.progress)
        renderer.hasFrame = true
        renderer.progress = 0f
        renderer.dirty = true
        requestRender()
        visibility = VISIBLE
    }

    /** 正在翻（手指拖拽中或松手后的动画中）：连点翻页要据此排队/忽略，避免动画被打断。 */
    var busy = false
        private set

    /** 拖拽过程中由手势层调用：progress 0 = 没翻，1 = 完全翻开。 */
    fun setProgress(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        renderer.progress = p
        requestRender()
    }

    /**
     * 松手。[velocity] 单位是「每秒翻过多少页」（正数=继续往前翻）。
     * 距离或速度够就翻过去，否则回弹。
     */
    fun release(progress: Float, velocity: Float) {
        val turn = willTurn(progress, velocity)
        val target = if (turn) 1f else 0f
        renderer.animateTo(target) { done ->
            turnDone(done >= 1f)
        }
        requestRender()
    }

    /** 松手/轻点这一下到底会不会翻过去——上层要据此先把底下那页换成下一页。 */
    fun willTurn(progress: Float, velocity: Float): Boolean = progress > 0.35f || velocity > 1.1f

    private fun turnDone(turned: Boolean) {
        busy = false
        // 先通知上层：它可能会立刻接上下一次翻页（连点排队），
        // 那样 begin() 已经换好了新帧，这里就不能再拆帧/隐藏，否则下一页永远翻不完。
        if (turned) onTurned?.invoke() else onCancelled?.invoke()
        if (busy) return

        if (turned) {
            // 新页的位图要几帧才画出来，先别收 GL 层，否则中间会闪一下「空白纸」。
            renderer.hasFrame = false
            requestRender()
            postDelayed({ if (!busy) visibility = INVISIBLE }, 90L)
        } else {
            visibility = INVISIBLE
            renderer.hasFrame = false
            requestRender()
        }
    }

    /** 直接取消（例如 Activity 退出、切页失败）。 */
    fun abort() {
        busy = false
        visibility = INVISIBLE
        renderer.cancelAnimation()
        renderer.hasFrame = false
        requestRender()
    }

    private class Frame(
        val bitmap: Bitmap,
        val rect: RectF,
        val viewWidth: Int,
        val viewHeight: Int,
        val progress: Float,
    )

    private class CurlRenderer : Renderer {

        @Volatile var progress = 0f
        @Volatile var dirty = false
        @Volatile var hasFrame = false
        /** GL 线程不能直接碰 View，回调回主线程/触发重绘都从这里走。 */
        var postToMain: ((Runnable) -> Unit)? = null
        var requestRenderCallback: (() -> Unit)? = null
        var pending = Frame(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), RectF(), 1, 1, 0f)

        private var program = 0
        private var vertexBuffer: FloatBuffer? = null
        private var indexCount = 0
        private var texture = 0
        private var texWidth = 1
        private var texHeight = 1
        private var uProgress = 0
        private var uRadius = 0
        private var uRect = 0
        private var uTex = 0
        private var uPageAspect = 0
        private var uMode = 0
        private var uFoldX = 0
        private var uShadow = 0
        private var uMirror = 0
        /** 1 = 向后翻（左边卷起），几何左右镜像。 */
        @Volatile var mirror = 0f

        private var animating = false
        private var animTarget = 0f
        private var animStart = 0f
        private var animStartTime = 0L
        private var animDuration = 0L
        private var animCallback: ((Float) -> Unit)? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            uProgress = GLES20.glGetUniformLocation(program, "uProgress")
            uRadius = GLES20.glGetUniformLocation(program, "uRadius")
            uRect = GLES20.glGetUniformLocation(program, "uRect")
            uTex = GLES20.glGetUniformLocation(program, "uTex")
            uPageAspect = GLES20.glGetUniformLocation(program, "uPageAspect")
            uMode = GLES20.glGetUniformLocation(program, "uMode")
            uFoldX = GLES20.glGetUniformLocation(program, "uFoldX")
            uShadow = GLES20.glGetUniformLocation(program, "uShadow")
            uMirror = GLES20.glGetUniformLocation(program, "uMirror")
            buildMesh()
            texture = 0
            uploadPendingIfNeeded()
            Log.i(TAG, "GL ready program=$program")
            // 先把表面清成透明：RENDERMODE_WHEN_DIRTY 下不主动画一帧的话，
            // SurfaceView 的缓冲内容是未定义的（实测是黑的），会把下面整页盖住。
            requestRenderCallback?.invoke()
        }

        /** 网格：横向密（卷曲发生在 x 方向），纵向两格就够。 */
        private fun buildMesh() {
            val cols = 64
            val rows = 2
            val verts = FloatArray((cols + 1) * (rows + 1) * 2)
            var i = 0
            for (r in 0..rows) {
                for (c in 0..cols) {
                    verts[i++] = c.toFloat() / cols
                    verts[i++] = r.toFloat() / rows
                }
            }
            val indices = ArrayList<Short>(cols * rows * 6)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val a = (r * (cols + 1) + c).toShort()
                    val b = (a + 1).toShort()
                    val d = ((r + 1) * (cols + 1) + c).toShort()
                    val e = (d + 1).toShort()
                    indices.add(a); indices.add(b); indices.add(d)
                    indices.add(b); indices.add(e); indices.add(d)
                }
            }
            indexCount = indices.size
            val vbb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            vbb.asFloatBuffer().put(verts).position(0)
            vertexBuffer = vbb.asFloatBuffer()
            val ibb = ByteBuffer.allocateDirect(indexCount * 2).order(ByteOrder.nativeOrder())
            ibb.asShortBuffer().put(indices.toShortArray()).position(0)
            indexBuffer = ibb.asShortBuffer()
        }

        private var indexBuffer: java.nio.ShortBuffer? = null

        /** 上传纹理；失败（没有 GL 上下文/没抢到 id）时保持 dirty，下一帧重试。 */
        fun uploadPendingIfNeeded() {
            if (!dirty) return
            if (texture == 0) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                texture = ids[0]
                if (texture == 0) return
            }
            val frame = pending
            texWidth = frame.bitmap.width
            texHeight = frame.bitmap.height
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame.bitmap, 0)
            dirty = false
            Log.i(TAG, "texture ${texWidth}x$texHeight id=$texture")
        }

        fun animateTo(target: Float, callback: (Float) -> Unit) {
            animating = true
            animTarget = target
            animStart = progress
            animStartTime = System.nanoTime()
            // 距离远就多花点时间，但别让用户等：0.18–0.30 秒。
            val dist = kotlin.math.abs(target - progress)
            animDuration = (140 + 200 * dist).toLong().coerceIn(140L, 340L) * 1_000_000L
            animCallback = callback
        }

        fun cancelAnimation() {
            animating = false
            animCallback = null
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // 表面可能比纹理晚准备好，这里每帧都补一次上传
            if (dirty) uploadPendingIfNeeded()

            if (animating) {
                val t = ((System.nanoTime() - animStartTime) / animDuration.toFloat()).coerceIn(0f, 1f)
                // ease-out：停下来的时候不再有突然的速度感
                val e = 1f - (1f - t) * (1f - t)
                progress = animStart + (animTarget - animStart) * e
                if (t >= 1f) {
                    animating = false
                    val cb = animCallback
                    animCallback = null
                    val callback = cb
                    postToMain?.invoke(Runnable { callback?.invoke(progress) })
                }
            }

            val frame = pending
            if (!hasFrame || texture == 0 || frame.viewWidth <= 1) return

            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glUniform1f(uProgress, progress)
            // 半径按纸宽比例给：纸越宽卷筒越明显
            GLES20.glUniform1f(uRadius, 0.16f + 0.06f * (1f - progress))
            GLES20.glUniform4f(
                uRect,
                frame.rect.left / frame.viewWidth * 2f - 1f,
                1f - frame.rect.bottom / frame.viewHeight * 2f,
                frame.rect.width() / frame.viewWidth * 2f,
                frame.rect.height() / frame.viewHeight * 2f,
            )
            GLES20.glUniform1f(uPageAspect, frame.rect.width() / frame.rect.height())

            val vbo = vertexBuffer ?: return
            val ibo = indexBuffer ?: return
            val posLoc = GLES20.glGetAttribLocation(program, "aPos")
            vbo.position(0)
            GLES20.glEnableVertexAttribArray(posLoc)
            GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vbo)

            // 底下不再铺假纸：翻起来的地方直接露出 GL 表面下面的真实下一页
            // （MainActivity 在松手决定翻过去时就先把 base 层切到下一页）。
            // 这样"翻过去"不用两张纸对齐，也不会出现白块和真页打架。

            // 只画正在被翻起来的这一页（带弯曲、光照明暗）。
            GLES20.glUniform1i(uMode, 0)
            GLES20.glUniform1f(uProgress, progress)
            GLES20.glUniform1f(uFoldX, 1f - progress)
            GLES20.glUniform1f(uShadow, progress)
            GLES20.glUniform1f(uMirror, mirror)
            ibo.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, ibo)

            GLES20.glDisableVertexAttribArray(posLoc)

            // 动画期间自驱动下一帧（RENDERMODE_WHEN_DIRTY）
            if (animating) requestRenderCallback?.invoke()
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            pending = Frame(pending.bitmap, pending.rect, width, height, pending.progress)
        }
    }

    private companion object {
        const val TAG = "PaperInkCurl"

        const val VERTEX_SHADER = """
            uniform float uProgress;
            uniform float uRadius;
            uniform float uFoldX;    // 折线位置（1-progress），底衬那遍也要用
            uniform float uShadow;   // 阴影强度（真实进度）
            uniform float uMirror;   // 1 = 向后翻（从左边卷起），几何左右镜像
            uniform vec4 uRect;      // x, y, w, h（NDC）
            attribute vec2 aPos;     // 页面 UV，[0,1]
            varying vec2 vUV;
            varying vec3 vNormal;
            varying float vTheta;
            varying float vFold;
            varying float vShade;
            varying float vX;        // 卷曲方向上的参数坐标（0=起卷边，1=另一边）

            void main() {
                float fold = 1.0 - uProgress;
                vec2 p = aPos;
                // 向后翻就是把整段动画左右镜像：在 q 空间里仍然是"从右边卷"，
                // 输出时再把 x 镜像回去，采样坐标 vUV 依旧跟着纸本身。
                float q = mix(p.x, 1.0 - p.x, uMirror);
                vec3 pos = vec3(q, p.y, 0.0);
                vec3 n = vec3(0.0, 0.0, 1.0);
                float theta = 0.0;

                if (q > fold) {
                    // 折线外侧绕竖直圆筒卷起来
                    theta = (q - fold) / max(uRadius, 0.02);
                    pos.x = fold + uRadius * sin(theta);
                    pos.z = uRadius * (1.0 - cos(theta));
                    n = normalize(vec3(-sin(theta), 0.0, cos(theta)));
                }

                // 一点假透视：z 越大越靠近眼睛，整片轻微放大
                float near = 1.0 + pos.z * 0.55;
                pos.x = (pos.x - 0.5) * near + 0.5;
                pos.y = (pos.y - 0.5) * near + 0.5;

                float screenX = mix(pos.x, 1.0 - pos.x, uMirror);
                n.x = mix(n.x, -n.x, uMirror);

                vUV = vec2(p.x, 1.0 - p.y);   // 位图 y 轴朝下
                vNormal = n;
                vTheta = theta;
                vFold = uFoldX;
                vShade = smoothstep(0.0, 0.22, uShadow);
                vX = q;

                gl_Position = vec4(
                    uRect.x + screenX * uRect.z,
                    uRect.y + pos.y * uRect.w,
                    0.0,
                    1.0
                );
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform int uMode;        // 0 = 正在翻的那一页；1 = 下面的纸
            varying vec2 vUV;
            varying vec3 vNormal;
            varying float vTheta;
            varying float vFold;
            varying float vShade;
            varying float vX;         // 卷曲方向参数（左右翻通用，阴影按它算）

            void main() {
                vec3 light = normalize(vec3(-0.35, 0.55, 0.75));
                vec3 n = normalize(vNormal);
                // 翻过去之后看到的是纸背：不显示图文，用米白纸色
                bool back = cos(vTheta) < 0.0;
                vec3 base = (uMode == 1)
                    ? vec3(0.86, 0.845, 0.815)   // 底衬那页纸：压暗一点，过曝会变成纯白块
                    : (back ? vec3(0.949, 0.929, 0.898) : texture2D(uTex, vUV).rgb);

                float diff = max(dot(n, light), 0.0);
                float ambient = back ? 0.80 : 0.90;
                float shade = ambient + 0.35 * diff;
                // 纸背是圆筒：越靠近折线越亮，卷到背面越暗（没有这条会变成一条死灰带）
                if (back) shade *= 0.70 + 0.34 * abs(cos(vTheta));

                // 高光：纸面偏哑光，但卷起来那一条会有明显反光
                vec3 h = normalize(light + vec3(0.0, 0.0, 1.0));
                float spec = pow(max(dot(n, h), 0.0), 26.0) * 0.42;

                // 折线附近的投影：卷起的纸挡住了光，靠折线越近越暗
                float nearFold = exp(-abs(vX - vFold) * 14.0) * vShade;
                shade -= nearFold * (uMode == 1 ? 0.16 : 0.22);

                // 完全平放（进度 0）时不要有高光残留
                spec *= smoothstep(0.02, 0.30, vShade);
                if (uMode == 1) spec = 0.0;

                vec3 color = base * shade + vec3(spec);
                gl_FragColor = vec4(color, 1.0);
            }
        """

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("shader compile failed: $log")
            }
            return shader
        }

        fun buildProgram(vertex: String, fragment: String): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs)
            GLES20.glAttachShader(program, fs)
            GLES20.glLinkProgram(program)
            val status = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                throw IllegalStateException("program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            return program
        }
    }
}
