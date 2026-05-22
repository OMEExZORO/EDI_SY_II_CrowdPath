package com.crowdpath.app.ui.mapping

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.crowdpath.app.data.models.Pose3D
import com.crowdpath.app.ui.theme.CyanAccent
import com.crowdpath.app.ui.theme.ElectricBlue
import com.crowdpath.app.ui.theme.NavyCard
import com.crowdpath.app.ui.theme.SlateText
import com.google.ar.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Composable that embeds an ARCore camera preview with real-time 3D pose tracking.
 *
 * Uses the ArSessionFactory to properly categorise availability, then:
 *  - If ARCore works: shows live camera feed with tracking indicator
 *  - If unavailable: shows a clean "Smart Step Tracking" card (not a red error)
 */
@Composable
fun ArCameraView(
    isActive: Boolean,
    onPoseUpdated: (Pose3D) -> Unit,
    onTrackingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isActive) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Build session using proper availability factory
    val sessionResult = remember {
        buildArSession(context)
    }

    when (sessionResult) {
        is ArSessionResult.Unavailable -> {
            PdrStatusCard(
                reason = sessionResult.reason,
                modifier = modifier
            )
            // Signal to parent that we are in PDR mode (not tracking via ARCore)
            LaunchedEffect(Unit) { onTrackingChanged(false) }
            return
        }

        is ArSessionResult.Available -> {
            val arSession = sessionResult.session
            val renderer = remember {
                ArGlRenderer(arSession, context, onPoseUpdated, onTrackingChanged)
            }
            var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            try { arSession.resume() } catch (e: Exception) {
                                Log.e(TAG_AR, "Session resume failed: ${e.message}")
                            }
                            glView?.onResume()
                        }
                        Lifecycle.Event.ON_PAUSE -> {
                            glView?.onPause()
                            try { arSession.pause() } catch (_: Exception) {}
                        }
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    try { arSession.pause(); arSession.close() } catch (_: Exception) {}
                }
            }

            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        preserveEGLContextOnPause = true
                        setEGLContextClientVersion(2)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        glView = this
                    }
                },
                modifier = modifier
            )
        }
    }
}

// ── PDR Status Card (shown when ARCore unavailable) ────────────────────────

@Composable
private fun PdrStatusCard(
    reason: ArUnavailableReason,
    modifier: Modifier = Modifier
) {
    // Gentle pulsing animation for the step icon
    val infiniteTransition = rememberInfiniteTransition(label = "pdr_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue  = 1f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pdr_alpha"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(ElectricBlue.copy(alpha = 0.4f), CyanAccent.copy(alpha = 0.2f))),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.DirectionsWalk,
                contentDescription = null,
                tint     = ElectricBlue.copy(alpha = alpha),
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Smart Step Tracking",
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize   = 15.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                reason.toUiMessage(),
                color    = SlateText,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

// ── Constants ──────────────────────────────────────────────────────────────
private const val TAG_AR = "ArCameraView"

// ── OpenGL ES 2.0 Renderer ─────────────────────────────────────────────────

private class ArGlRenderer(
    private val session: Session,
    private val context: Context,
    private val onPoseUpdated: (Pose3D) -> Unit,
    private val onTrackingChanged: (Boolean) -> Unit
) : GLSurfaceView.Renderer {

    private var cameraTextureId: Int = -1
    private var textureReady = false

    private var bgProgram: Int = 0
    private var bgPosAttrib: Int = 0
    private var bgTexAttrib: Int = 0
    private var bgTexUniform: Int = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val quadVerts = allocBuf(floatArrayOf(
        -1f, -1f, 0f,  -1f, +1f, 0f,  +1f, -1f, 0f,  +1f, +1f, 0f
    ))
    private val quadUvs = allocBuf(floatArrayOf(
        0f, 1f,  0f, 0f,  1f, 1f,  1f, 0f
    ))
    private val transformedUvs = allocBuf(floatArrayOf(
        0f, 1f,  0f, 0f,  1f, 1f,  1f, 0f
    ))

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.06f, 0.09f, 0.12f, 1.0f)

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        cameraTextureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        session.setCameraTextureName(cameraTextureId)
        textureReady = true

        bgProgram   = buildProgram(CAMERA_VS, CAMERA_FS)
        bgPosAttrib = GLES20.glGetAttribLocation(bgProgram, "aPosition")
        bgTexAttrib = GLES20.glGetAttribLocation(bgProgram, "aTexCoord")
        bgTexUniform = GLES20.glGetUniformLocation(bgProgram, "uTexture")
    }

    @Suppress("DEPRECATION")
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
        session.setDisplayGeometry(rotation, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!textureReady) return

        val frame: Frame = try { session.update() } catch (_: Exception) { return }

        if (frame.hasDisplayGeometryChanged()) {
            frame.transformDisplayUvCoords(quadUvs, transformedUvs)
        }

        drawCameraQuad()

        val camera   = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) {
            val t = camera.pose.translation
            val pose = Pose3D(x = t[0], y = -t[2], z = t[1])
            mainHandler.post { onPoseUpdated(pose); onTrackingChanged(true) }
        } else {
            mainHandler.post { onTrackingChanged(false) }
        }
    }

    private fun drawCameraQuad() {
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(bgTexUniform, 0)
        GLES20.glEnableVertexAttribArray(bgPosAttrib)
        GLES20.glVertexAttribPointer(bgPosAttrib, 3, GLES20.GL_FLOAT, false, 0, quadVerts)
        GLES20.glEnableVertexAttribArray(bgTexAttrib)
        GLES20.glVertexAttribPointer(bgTexAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedUvs)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(bgPosAttrib)
        GLES20.glDisableVertexAttribArray(bgTexAttrib)
        GLES20.glDepthMask(true)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val vsId = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vsId)
        GLES20.glAttachShader(prog, fsId)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vsId)
        GLES20.glDeleteShader(fsId)
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG_AR, "Shader error: ${GLES20.glGetShaderInfoLog(id)}")
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    private fun allocBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(data); position(0) }

    companion object {
        private const val CAMERA_VS = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """
        private const val CAMERA_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
