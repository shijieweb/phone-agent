package net.de5.phoneagent

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.util.Size
import android.util.TypedValue
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 「摄像头看图」页（v0.9.0-pad12）—— 手机主人举着手机对着一处，打一句话按「发送」，
 * 这一帧和这句话就送到中转站，那边的操作员看得见（SPEC.md 第 3.29 节）。
 *
 * ## 为什么不用截屏
 *
 * 这个 App 取画面一直靠 Screenshot（screencap / AccessibilityService.takeScreenshot）。
 * 相机预览在 Android 上是**硬件叠加层**：GPU 直接合成到显示控制器，绕过了 SurfaceFlinger
 * 的合成缓冲，所以截屏截到的是一块黑（或上一次的残影），不是镜头里的画面。这不是权限
 * 问题，加多少权限都截不到。要拿相机画面只有一条正路：Camera2 + ImageReader(JPEG)，
 * 让相机把一帧 JPEG 直接写进我们自己的缓冲区。
 *
 * ## 为什么预览和拍照要分两个尺寸
 *
 * 预览要的是「看得见、不卡」——挑一个 16:9 的尺寸；拍照要的是「够看清、传得动」——挑
 * 接近 1280x720 的 JPEG 尺寸，再按 CamPlan.MAX_PX / CamPlan.JPEG_QUALITY 重压一次。
 * 两个尺寸都从 StreamConfigurationMap 里挑，**都不取最大值**：这台机器最大能到 4000x3000，
 * 一帧 2 MB 在手机上行链路里要传好几秒，而操作员那边看的是 1280 宽的屏。
 *
 * ## 线程
 *
 * 相机的所有调用（open / createCaptureSession / capture / close）都在自己的 HandlerThread
 * 上，这是 Camera2 的要求：CameraDevice 的回调线程必须是带 Looper 的单一线程，主线程上做
 * 这些会卡界面，而且 close 与 capture 抢同一个对象会抛 IllegalStateException。解码、重压、
 * 上传在另一个普通线程上跑——那几秒里相机还得继续预览。
 *
 * 这一页不导出（exported = false，不进最近任务）：只有配置页那个按钮能进来。
 */
class CameraActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var previewView: TextureView
    private lateinit var inputView: EditText
    private lateinit var statusView: TextView
    private lateinit var sendButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private val camThread = HandlerThread("cam-look")
    private lateinit var camHandler: Handler

    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    /** Sensor orientation, read when the camera opens; used to turn the frame upright. */
    private var sensorOrientation = 0

    /** True between the tap on 发送 and the answer: blocks a second capture. */
    @Volatile
    private var busy = false

    /** True once the surface listener is installed, so it is not installed twice. */
    private var started = false

    /** True while openCamera is in flight, so a surface callback cannot open twice. */
    @Volatile
    private var opening = false

    /** The sentence that goes with the frame currently in flight. */
    @Volatile
    private var pendingText: String = ""

    /**
     * True while the operator has been told this page is showing the camera.
     *
     * It exists so the open and close notes come in pairs: onResume can run twice
     * without an onPause in between (a permission dialog returning), and a second
     * cam-open would read to the operator as a second opening of a camera that
     * never closed.
     */
    @Volatile
    private var modeOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        title = UiText.CAMERA

        // The phone is held up at something and the owner talks into it; the screen
        // going dark mid-sentence would end the whole interaction.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(24), dp(24), dp(24), dp(16))

        // weight 1: the preview takes every pixel the text box and the buttons do
        // not need. A smaller preview is what makes the owner guess what is in frame.
        previewView = TextureView(this)
        root.addView(
            previewView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        )

        statusView = TextView(this)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        statusView.setPadding(0, dp(8), 0, dp(8))
        root.addView(statusView, matchWidth())

        inputView = EditText(this)
        inputView.hint = HINT_TEXT
        inputView.setSingleLine(false)
        inputView.minLines = 2
        inputView.maxLines = 4
        inputView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // The IME's own voice key types into whatever has focus, so this box is the
        // whole speech-to-text path: no recogniser, no network, no extra permission.
        inputView.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(CamPlan.MAX_TEXT))
        root.addView(inputView, matchWidth())

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        sendButton = addButton(buttons, SEND_TEXT, 1.0f) { send() }
        addButton(buttons, BACK_TEXT, 1.0f) { finish() }
        root.addView(buttons, matchWidth())

        setContentView(root)

        camThread.start()
        camHandler = Handler(camThread.looper)

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            statusView.text = NEED_PERMISSION_TEXT
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            statusView.text = ""
            startCamera()
        } else {
            // Nothing on this page can work without the camera, so say it once and
            // leave 发送 dead rather than letting it fail with a mystery later.
            statusView.text = CamPlan.errorLine(DENIED_TEXT)
            sendButton.isEnabled = false
        }
    }

    // ---------------------------------------------------------------- camera --

    private fun startCamera() {
        if (started) return
        started = true
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                openCamera(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                // The preview buffer size is fixed to the chosen camera size; the view
                // just scales it, so a rotation needs no session rebuild.
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeCamera()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val existing = previewView.surfaceTexture
        if (existing != null) openCamera(existing)
    }

    /** Must be called on the UI thread: TextureView hands out its texture only there. */
    private fun openCamera(texture: SurfaceTexture) {
        if (opening || camera != null) return
        opening = true
        camHandler.post {
            val manager = getSystemService(CameraManager::class.java)
            if (manager == null) {
                opening = false
                reportError(OPEN_FAILED_TEXT)
                return@post
            }
            try {
                val id = backCameraId(manager)
                if (id == null) {
                    opening = false
                    reportError(NO_BACK_CAMERA_TEXT)
                    return@post
                }
                val characteristics = manager.getCameraCharacteristics(id)
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map == null) {
                    opening = false
                    reportError(OPEN_FAILED_TEXT)
                    return@post
                }

                // Preview and still are picked separately and both on purpose: see
                // the class comment. CamPlan.pickSize never returns the largest size.
                val previewPicked = CamPlan.pickSize(
                    map.getOutputSizes(SurfaceTexture::class.java).map { intArrayOf(it.width, it.height) },
                    PREVIEW_TARGET_W,
                    PREVIEW_TARGET_H
                )
                val jpegPicked = CamPlan.pickSize(
                    map.getOutputSizes(ImageFormat.JPEG).map { intArrayOf(it.width, it.height) },
                    CamPlan.MAX_PX,
                    CamPlan.MAX_PX * 9 / 16
                )
                if (previewPicked == null || jpegPicked == null) {
                    opening = false
                    reportError(OPEN_FAILED_TEXT)
                    return@post
                }
                val pv = Size(previewPicked[0], previewPicked[1])
                val jp = Size(jpegPicked[0], jpegPicked[1])

                // Two buffers: one frame is being encoded while the next arrives, and
                // maxImages 1 would make the second acquireLatestImage drop the first.
                val imageReader = ImageReader.newInstance(jp.width, jp.height, ImageFormat.JPEG, 2)
                imageReader.setOnImageAvailableListener({ source -> onFrame(source) }, camHandler)
                reader = imageReader

                texture.setDefaultBufferSize(pv.width, pv.height)
                val surface = Surface(texture)
                previewSurface = surface

                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        camera = device
                        opening = false
                        createSession(device, surface, imageReader)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        camera = null
                        opening = false
                        reportError(DISCONNECTED_TEXT)
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        camera = null
                        opening = false
                        reportError(OPEN_FAILED_TEXT + " (" + error + ")")
                    }
                }, camHandler)
            } catch (t: Throwable) {
                opening = false
                reportError(describe(t))
            }
        }
    }

    /**
     * One session holding both surfaces: the preview Surface and the ImageReader
     * Surface. Camera2 needs both targets in the *same* session — a still capture is
     * a request on that session with the JPEG target attached, and a device forced to
     * tear the session down to switch targets loses the frame it was about to give us.
     */
    private fun createSession(
        device: CameraDevice,
        previewTarget: Surface,
        jpegReader: ImageReader
    ) {
        try {
            val targets = listOf(previewTarget, jpegReader.surface)
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(previewTarget)
                        setAutoFocus(builder)
                        configured.setRepeatingRequest(builder.build(), null, camHandler)
                        uiHandler.post {
                            if (isFinishing || isDestroyed) return@post
                            statusView.text = READY_TEXT
                        }
                    } catch (t: Throwable) {
                        reportError(describe(t))
                    }
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    reportError(SESSION_FAILED_TEXT)
                }
            }, camHandler)
        } catch (t: Throwable) {
            reportError(describe(t))
        }
    }

    private fun closeCamera() {
        val activeSession = session
        val activeCamera = camera
        val activeReader = reader
        val activeSurface = previewSurface
        session = null
        camera = null
        reader = null
        previewSurface = null
        opening = false
        if (!camThread.isAlive) return
        camHandler.post {
            try {
                activeSession?.close()
            } catch (t: Throwable) {
                // Already gone; nothing to report.
            }
            try {
                activeCamera?.close()
            } catch (t: Throwable) {
                // Same.
            }
            try {
                activeReader?.close()
            } catch (t: Throwable) {
                // Same.
            }
            try {
                activeSurface?.release()
            } catch (t: Throwable) {
                // Same.
            }
        }
    }

    override fun onPause() {
        // Before closeCamera(): the note says "the camera is closing", and it has
        // to leave while the fact is still true, not after the teardown.
        if (modeOpen) {
            modeOpen = false
            postMode(CamPlan.MODE_CLOSE)
        }
        closeCamera()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!modeOpen) {
            modeOpen = true
            postMode(CamPlan.MODE_OPEN)
        }
        // Back from the permission dialog or the home screen: the surface may
        // already be there, in which case no callback will fire for it.
        val texture = if (started) previewView.surfaceTexture else null
        if (texture != null && camera == null) openCamera(texture)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        closeCamera()
        camThread.quitSafely()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ send --

    /**
     * 发送：先看有没有字，再等手停下来，然后拍一帧。
     *
     * 空文字直接拒绝而不是照发一张图：操作员收到一张没有问题的照片不知道要看
     * 什么，而手机主人会以为「已经发出去了」，两个人对着同一张图猜。
     */
    private fun send() {
        if (busy) return
        val text = CamPlan.clampText(inputView.text.toString())
        if (text.isEmpty()) {
            statusView.text = EMPTY_TEXT
            return
        }
        if (session == null || camera == null) {
            statusView.text = CamPlan.errorLine(NOT_READY_TEXT)
            return
        }
        busy = true
        sendButton.isEnabled = false
        statusView.text = WAITING_TEXT

        // The hand is still moving when the button is released; a frame grabbed now
        // is a blurred picture of the ceiling. CamPlan.SETTLE_MS is that wait.
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed) {
                busy = false
                return@postDelayed
            }
            camHandler.post { captureStill(text) }
        }, CamPlan.SETTLE_MS)
    }

    private fun captureStill(text: String) {
        val device = camera
        val activeSession = session
        val jpegReader = reader
        if (device == null || activeSession == null || jpegReader == null) {
            reportError(NOT_READY_TEXT)
            return
        }
        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(jpegReader.surface)
            setAutoFocus(builder)
            pendingText = text
            activeSession.capture(builder.build(), null, camHandler)
        } catch (t: Throwable) {
            pendingText = ""
            reportError(NOT_READY_TEXT)
            AgentLog.add("cam", "拍照失败: " + describe(t))
        }
    }

    /** Called on the camera thread the moment the JPEG lands in the ImageReader. */
    private fun onFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            out
        } catch (t: Throwable) {
            null
        } finally {
            image.close()
        }
        if (bytes == null || bytes.isEmpty()) {
            reportError(SHOT_FAILED_TEXT)
            return
        }
        val text = pendingText
        pendingText = ""
        // Decoding, re-encoding and uploading take seconds; the camera has to keep
        // previewing through them, so they run off the camera thread.
        Thread({ deliver(bytes, text) }, "cam-send").start()
    }

    /** Decode, shrink, turn upright, re-encode, upload, report. Runs off the UI thread. */
    private fun deliver(jpeg: ByteArray, text: String) {
        val shot = try {
            shrink(jpeg)
        } catch (t: Throwable) {
            AgentLog.add("cam", "重压失败: " + describe(t))
            reportError(SHOT_FAILED_TEXT)
            return
        }
        val name = CamPlan.blobName(System.currentTimeMillis(), suffix())
        // A fresh client per send: RcClient holds no state and opens a connection per
        // call, so this needs neither the service nor a prior hello.
        val client = RcClient(prefs.relayBase, prefs.secret, prefs.agentId, agentInfo())

        val upload = client.uploadBlob(name, shot.bytes)
        if (!upload.ok) {
            reportError(upload.error ?: UPLOAD_FAILED_TEXT)
            return
        }
        val note = client.postNote(CamPlan.noteBody(prefs.agentId, text, name, shot.width, shot.height))
        if (!note.ok) {
            reportError(note.error ?: NOTE_FAILED_TEXT)
            return
        }
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            busy = false
            sendButton.isEnabled = true
            statusView.text = CamPlan.summaryLine(
                shot.bytes.size,
                shot.width,
                shot.height,
                text.codePointCount(0, text.length)
            )
            inputView.setText("")
        }
    }

    /**
     * 告诉操作员这一页开了还是关了 (v0.11.0-pad12, SPEC.md §3.31).
     *
     * Off the UI thread: the relay can be slow, and blocking in onResume or
     * onPause blocks the page. A failure only goes to the log -- this is a side
     * note about the camera, not the frame the owner pressed 发送 for, so it must
     * not put an error banner over the preview.
     */
    private fun postMode(kind: String) {
        if (!CamPlan.modeNote(kind)) return
        val base = prefs.relayBase
        val secret = prefs.secret
        val id = prefs.agentId
        val info = agentInfo()
        val body = CamPlan.modeBody(id, kind)
        Thread({
            val client = RcClient(base, secret, id, info)
            val result = client.postNote(body)
            if (!result.ok) {
                AgentLog.add("cam", "相机状态上报失败(" + kind + "): " + (result.error ?: NOTE_FAILED_TEXT))
            }
        }, "cam-mode").start()
    }

    private data class Shot(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * 重压：解码 → 按 CamPlan.MAX_PX 缩 → 转正 → 按 CamPlan.JPEG_QUALITY 编回 JPEG。
     *
     * 转正放在这里而不是交给 JPEG 的 EXIF 方向标记：BitmapFactory 不读 EXIF，重压一次
     * 之后那个标记就没了，画面会躺着送到操作员眼前。所以像素在这里转一次，出来的文件
     * 不依赖任何标记。
     */
    private fun shrink(jpeg: ByteArray): Shot {
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalStateException("decode returned null")
        val scale = CamPlan.fitScale(raw.width, raw.height, CamPlan.MAX_PX)
        val resized = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).roundToInt().coerceAtLeast(1),
                (raw.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            raw
        }
        val degrees = uprightDegrees()
        val upright = if (degrees == 0) {
            resized
        } else {
            val matrix = Matrix()
            matrix.postRotate(degrees.toFloat())
            Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, matrix, true)
        }
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, CamPlan.JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        val width = upright.width
        val height = upright.height
        if (upright !== resized) upright.recycle()
        if (resized !== raw) resized.recycle()
        raw.recycle()
        if (bytes.isEmpty()) throw IllegalStateException("compress produced nothing")
        return Shot(bytes, width, height)
    }

    /**
     * How far to turn the sensor's frame so it matches the way the phone is held.
     *
     * Sensor orientation is fixed to the hardware; the display rotation is what
     * changed. Back camera: subtract — the usual formula, and the reason a portrait
     * frame from a landscape sensor comes out sideways.
     */
    private fun uprightDegrees(): Int {
        val rotation = try {
            windowManager.defaultDisplay.rotation
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ((sensorOrientation - deviceDegrees) + 360) % 360
    }

    /** Four hex nibbles for the blob name; see CamPlan.blobName. */
    private fun suffix(): String {
        val value = (System.nanoTime() xor (Math.random() * Int.MAX_VALUE).toLong()) and 0xFFFFL
        val hex = java.lang.Long.toHexString(value)
        var padded = hex
        while (padded.length < 4) padded = "0" + padded
        return padded
    }

    private fun agentInfo(): AgentInfo = AgentInfo(
        hostname = android.os.Build.DEVICE,
        user = android.os.Build.MODEL.replace(' ', '_'),
        os = "android-" + android.os.Build.VERSION.RELEASE,
        ver = BuildConfig.VERSION_NAME
    )

    // ----------------------------------------------------------------- utils --

    private fun setAutoFocus(builder: CaptureRequest.Builder) {
        try {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        } catch (t: Throwable) {
            // A fixed-focus camera has no AF mode; the frame is still usable.
        }
    }

    private fun backCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    /** One place that turns a reason into the red line, from any thread. */
    private fun reportError(reason: String) {
        uiHandler.post {
            if (isFinishing || isDestroyed) return@post
            busy = false
            sendButton.isEnabled = true
            statusView.text = CamPlan.errorLine(reason)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun addButton(
        parent: LinearLayout,
        text: String,
        weight: Float,
        onClick: () -> Unit
    ): Button {
        val button = Button(this)
        button.text = text
        button.isAllCaps = false
        button.setOnClickListener { onClick() }
        parent.addView(
            button,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        )
        return button
    }

    private companion object {
        const val REQ_CAMERA = 91

        /** A 16:9 preview target; the actual size comes from the camera's own list. */
        const val PREVIEW_TARGET_W = 1280
        const val PREVIEW_TARGET_H = 720

        const val HINT_TEXT = "说一句：哪里不对 / 要我看什么"
        const val SEND_TEXT = "发送"
        const val BACK_TEXT = "返回"

        const val READY_TEXT = "对准要我看的东西，打一句话，按发送"
        const val WAITING_TEXT = "正在拍…"
        const val EMPTY_TEXT = "先打一句话"
        const val NEED_PERMISSION_TEXT = "需要相机权限"
        const val DENIED_TEXT = "没有相机权限，去系统设置里给这个应用打开"
        const val NOT_READY_TEXT = "相机还没准备好"
        const val OPEN_FAILED_TEXT = "打不开相机"
        const val NO_BACK_CAMERA_TEXT = "这台机器没有后置相机"
        const val SESSION_FAILED_TEXT = "相机不支持同时预览和拍照"
        const val DISCONNECTED_TEXT = "相机被别的应用抢走了"
        const val SHOT_FAILED_TEXT = "这一帧没取到"
        const val UPLOAD_FAILED_TEXT = "图没传上去"
        const val NOTE_FAILED_TEXT = "文字没传上去"
    }
}
